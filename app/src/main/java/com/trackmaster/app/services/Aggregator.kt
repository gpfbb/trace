package com.trackmaster.app.services

import com.trackmaster.app.core.DailySummary
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.SegmentStatus
import com.trackmaster.app.core.StaySegment
import com.trackmaster.app.core.VizNode
import com.trackmaster.app.utils.buildFenceTree
import com.trackmaster.app.storage.Repository

/**
 * T+1 批处理聚合器 — 跨天切割、空间向上累加、分类解耦
 *
 * 核心规则：
 * 1. 仅在 00:00 或下次启动时聚合前一天的 Segment
 * 2. 跨天 Segment 按日界切割，归属到对应日期
 * 3. 父级空间时长 = Σ(子叶子时长) + 父级独立停留时长
 * 4. 同一分钟绝不可重复计算
 * 5. 分类仅绑定叶子围栏，父级绝不继承子级分类
 */
class Aggregator(
    fences: List<Geofence>,
    private val repo: Repository,
) {
    private val fenceMap: Map<String, Geofence>
    private val childrenMap: Map<String, List<Geofence>>
    private val leafSet: Set<String>
    private val leavesByDepth: List<Geofence>

    init {
        val tree = buildFenceTree(fences)
        fenceMap = tree.fenceMap
        childrenMap = tree.childrenMap
        leafSet = tree.leafFences.map { it.id }.toSet()
        leavesByDepth = tree.leavesByDepth
    }

    // ============================================================
    // T+1 聚合主流程
    // ============================================================

    /**
     * 对指定日期执行聚合
     *
     * @param dateKey yyyy-MM-dd
     */
    suspend fun aggregate(dateKey: String): List<DailySummary> {
        val segments = repo.getSegmentsByDate(dateKey)

        // 步骤 1：跨天切割 + 按叶子围栏汇总分钟
        val (leafMinutes, leafCategoryMinutes) = accumulateLeafDurations(segments, dateKey)

        // 步骤 2：构建完整的汇总列表（含父级向上累加）
        val summaries = buildSummaries(leafMinutes, leafCategoryMinutes, dateKey)

        // 步骤 3：持久化
        repo.saveDailySummaries(dateKey, summaries)

        return summaries
    }

    // ============================================================
    // 跨天切割 + 叶子围栏时长汇总（去重分钟）
    // ============================================================

    private data class LeafAccumResult(
        val leafMinutes: Map<String, Int>,
        val leafCategoryMinutes: Map<String, Map<String, Int>>,
    )

    private fun accumulateLeafDurations(
        segments: List<StaySegment>,
        dateKey: String,
    ): LeafAccumResult {
        val leafMinutes = mutableMapOf<String, Int>()
        val leafCategoryMinutes = mutableMapOf<String, MutableMap<String, Int>>()

        // 当天 00:00 和次日 00:00 的时间戳
        val dayStart = parseDateKey(dateKey)
        val dayEnd = dayStart + 86_400_000L

        // 跨 Segment 去重集合
        val dedupeSet = mutableSetOf<String>()

        for (seg in segments) {
            if (seg.endTs == null) continue // 跳过未关闭的
            if (seg.leafGeofenceId !in leafSet) continue // 仅处理叶子

            // 跨天切割：取 Segment 与当日的交集
            val effectiveStart = maxOf(seg.startTs, dayStart)
            val effectiveEnd = minOf(seg.endTs, dayEnd)

            if (effectiveStart >= effectiveEnd) continue

            // 计算覆盖的分钟集合（去重）
            val startMin = effectiveStart / 60_000L
            val endMin = effectiveEnd / 60_000L

            var addedMinutes = 0
            for (m in startMin..endMin) {
                val minuteKey = "${seg.leafGeofenceId}:$m"
                if (dedupeSet.add(minuteKey)) {
                    addedMinutes++
                }
            }

            val fenceId = seg.leafGeofenceId
            leafMinutes[fenceId] = (leafMinutes[fenceId] ?: 0) + addedMinutes

            // 分类映射
            val fence = fenceMap[fenceId]
            if (fence?.category != null) {
                val catMap = leafCategoryMinutes.getOrPut(fenceId) { mutableMapOf() }
                catMap[fence.category] = (catMap[fence.category] ?: 0) + addedMinutes
            }
        }

        return LeafAccumResult(leafMinutes, leafCategoryMinutes)
    }

    // ============================================================
    // 向上累加（空间聚合）
    // ============================================================

    private fun buildSummaries(
        leafMinutes: Map<String, Int>,
        leafCategoryMinutes: Map<String, Map<String, Int>>,
        dateKey: String,
    ): List<DailySummary> {
        val allMinutes = mutableMapOf<String, Int>()
        val allCategoryMap = mutableMapOf<String, Map<String, Int>>()

        // 按深度降序排列，确保先处理子级
        val sortedFences = fenceMap.values.sortedByDescending { it.depth }

        for (fence in sortedFences) {
            var totalMin = 0

            if (fence.id in leafSet) {
                // 叶子：取直接汇总值
                totalMin = leafMinutes[fence.id] ?: 0
                allCategoryMap[fence.id] = leafCategoryMinutes[fence.id] ?: emptyMap()
            } else {
                // 父级：累加所有子级
                val children = childrenMap[fence.id] ?: emptyList()
                for (child in children) {
                    totalMin += allMinutes[child.id] ?: 0
                }
                allCategoryMap[fence.id] = emptyMap() // 父级无分类
            }

            allMinutes[fence.id] = totalMin
        }

        // 构建输出
        return fenceMap.values.mapNotNull { fence ->
            val durationMin = allMinutes[fence.id] ?: 0
            if (durationMin > 0) {
                DailySummary(
                    date = dateKey,
                    geofenceId = fence.id,
                    name = fence.name,
                    spatialDurationMin = durationMin,
                    leafCategoryMap = allCategoryMap[fence.id] ?: emptyMap(),
                    isLeaf = fence.id in leafSet,
                    depth = fence.depth,
                )
            } else null
        }
    }

    // ============================================================
    // 可视化转换
    // ============================================================

    /**
     * 将 DailySummary 列表转换为 Sunburst / TreeMap 标准 JSON
     */
    fun toVizTree(summaries: List<DailySummary>): VizNode {
        val durationMap = summaries.associate { it.geofenceId to it.spatialDurationMin }

        // 找到顶级围栏（parentId === null 且有数据）
        val roots = fenceMap.values.filter { it.parentId == null && it.id in durationMap }

        if (roots.isEmpty()) {
            return VizNode(name = "root", children = emptyList())
        }

        return VizNode(
            name = "root",
            children = roots.map { buildVizNode(it, durationMap) },
        )
    }

    private fun buildVizNode(fence: Geofence, durationMap: Map<String, Int>): VizNode {
        val children = childrenMap[fence.id]
        val duration = durationMap[fence.id] ?: 0

        if (children.isNullOrEmpty()) {
            return VizNode(name = fence.name, value = duration)
        }

        val childNodes = children
            .filter { it.id in durationMap }
            .map { buildVizNode(it, durationMap) }

        return VizNode(
            name = fence.name,
            children = childNodes.ifEmpty { null },
        )
    }

    companion object {
        private fun parseDateKey(dateKey: String): Long {
            val parts = dateKey.split("-")
            val cal = java.util.Calendar.getInstance()
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
