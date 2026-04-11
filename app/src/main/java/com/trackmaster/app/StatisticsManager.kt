package com.trackmaster.app

import android.content.Context
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class FenceStatistics(
    val fenceId: String,
    val fenceName: String,
    val totalDurationSeconds: Long,
    val enterCount: Int,
    val categoryId: String?
)

/**
 * 围栏统计树的节点
 */
data class FenceStatisticTree(
    val fenceId: String,
    val fenceName: String,
    val color: String,
    val ownDurationSeconds: Long,  // 自身时长（不含子围栏）
    val totalDurationSeconds: Long, // 总时长（含子围栏）
    val children: List<FenceStatisticTree>,
    val isExpanded: Boolean = false,
    val depth: Int = 0
)

/**
 * 时间范围
 */
data class TimeRange(
    val startTime: Date,
    val endTime: Date
)

/**
 * 时间维度
 */
enum class TimeDimension {
    DAY,
    MONTH,
    YEAR
}

/**
 * 围栏统计管理器
 */
class StatisticsManager(private val context: Context) {

    private val dbHelper: GeoFenceDatabaseHelper
        get() = GeoFenceDatabaseHelper(context)

    private val app: TrackMasterApplication
        get() = TrackMasterApplication.instance

    /**
     * 获取统计数据（包含已完成记录 + 当前活跃会话的实时时长）
     */
    fun getStatistics(timeDimension: TimeDimension, timeRange: TimeRange): List<FenceStatistics> {
        android.util.Log.d("StatisticsManager", "getStatistics called: dimension=$timeDimension, range=${timeRange.startTime}-${timeRange.endTime}")

        val records = dbHelper.getAllStayRecords()
        val filteredRecords = records.filter { record ->
            val enterTime = record.enterTime
            enterTime >= timeRange.startTime && enterTime <= timeRange.endTime
        }
        android.util.Log.d("StatisticsManager", "Filtered records: ${filteredRecords.size}")

        val statsMap = mutableMapOf<String, FenceStatisticsBuilder>()

        // 添加已完成的停留记录
        for (record in filteredRecords) {
            val fenceId = record.fenceId
            val fenceName = record.fenceName ?: fenceId
            val duration = record.durationSeconds ?: 0L

            val builder = statsMap.getOrPut(fenceId) {
                FenceStatisticsBuilder(fenceId = fenceId, fenceName = fenceName)
            }
            builder.addDuration(duration)
            builder.incrementEnterCount()
        }

        // 添加当前活跃会话的实时时长（如果属于当前时间范围）
        // 注意：对于日视图，即使 enterTime 是昨天（跨午夜），只要会话还在进行中，也应该计入
        val activeSessions = app.stayDurationManager.activeSessions
        android.util.Log.d("StatisticsManager", "Active sessions: ${activeSessions.size}")
        val now = Date()
        for ((fenceId, session) in activeSessions) {
            // 活跃会话在时间范围内的情况：
            // 1. enterTime 在范围内（当天进入）
            // 2. enterTime 在范围之前，但会话还在进行（跨午夜的情况）
            val sessionEnded = false // 活跃会话没有结束时间
            val overlapsWithRange = session.enterTime <= timeRange.endTime && !sessionEnded
            if (overlapsWithRange) {
                // 计算实时停留时长（从进入到现在）
                val currentDuration = (now.time - session.enterTime.time) / 1000 + session.accumulatedDuration
                android.util.Log.d("StatisticsManager", "Active session $fenceId (${session.fenceName}): duration=${currentDuration}s, enterTime=${session.enterTime}")
                val builder = statsMap.getOrPut(fenceId) {
                    FenceStatisticsBuilder(fenceId = fenceId, fenceName = session.fenceName)
                }
                builder.addDuration(currentDuration)
                // 如果这个围栏之前没有被记录进入（当前活跃），也算一次进入
                if (!filteredRecords.any { it.fenceId == fenceId }) {
                    builder.incrementEnterCount()
                }
            }
        }

        val result = statsMap.values
            .map { it.build() }
            .sortedByDescending { it.totalDurationSeconds }
        android.util.Log.d("StatisticsManager", "getStatistics result: ${result.size} items")
        return result
    }

    /**
     * 构建围栏统计树（按父子关系）
     */
    suspend fun buildFenceStatisticTree(
        timeDimension: TimeDimension,
        timeRange: TimeRange
    ): List<FenceStatisticTree> = withContext(Dispatchers.IO) {
        // 1. 获取所有围栏
        val allFences = dbHelper.getAllFences()
        val fenceMap = allFences.associateBy { it.id }

        // 2. 获取统计数据（仅自身时长）
        val statistics = getStatistics(timeDimension, timeRange)
        val statsMap = statistics.associateBy { it.fenceId }

        // 3. 获取父子关系映射
        val parentMap = app.fenceTreeManager.getParentMap()

        // 4. 构建 parentId -> children 映射
        val childrenMap = mutableMapOf<String?, MutableList<String>>()
        for (fence in allFences) {
            val parentId = parentMap[fence.id]
            childrenMap.getOrPut(parentId) { mutableListOf() }.add(fence.id)
        }

        // 5. 递归构建树
        fun buildTree(fenceId: String, depth: Int, ownDuration: Long): FenceStatisticTree? {
            val fence = fenceMap[fenceId] ?: return null
            val stat = statsMap[fenceId]

            val childIds = childrenMap[fenceId] ?: emptyList()
            val childTrees = mutableListOf<FenceStatisticTree>()

            var totalChildrenDuration = 0L
            for (childId in childIds) {
                val childTree = buildTree(childId, depth + 1, statsMap[childId]?.totalDurationSeconds ?: 0L)
                if (childTree != null) {
                    childTrees.add(childTree)
                    totalChildrenDuration += childTree.totalDurationSeconds
                }
            }

            // 按总时长降序排列子节点
            childTrees.sortByDescending { it.totalDurationSeconds }

            // 自身时长 + 子节点时长 = 总时长
            val ownDur = stat?.totalDurationSeconds ?: ownDuration
            val totalDur = ownDur + totalChildrenDuration

            return FenceStatisticTree(
                fenceId = fenceId,
                fenceName = fence.name,
                color = fence.color,
                ownDurationSeconds = ownDur,
                totalDurationSeconds = totalDur,
                children = childTrees,
                isExpanded = false,
                depth = depth
            )
        }

        // 6. 获取顶级围栏并构建森林
        val topLevelIds = childrenMap[null] ?: emptyList()
        topLevelIds.mapNotNull { buildTree(it, 0, statsMap[it]?.totalDurationSeconds ?: 0L) }
            .sortedByDescending { it.totalDurationSeconds }
    }

    /**
     * 格式化时长
     */
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> String.format("%d小时%d分%d秒", hours, minutes, secs)
            minutes > 0 -> String.format("%d分%d秒", minutes, secs)
            else -> String.format("%d秒", secs)
        }
    }

    fun getDateRange(year: Int, month: Int, day: Int): TimeRange {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month)
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.time

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val end = calendar.time

        return TimeRange(start, end)
    }

    fun getMonthRange(year: Int, month: Int): TimeRange {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.time

        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val end = calendar.time

        return TimeRange(start, end)
    }

    fun getYearRange(year: Int): TimeRange {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, Calendar.JANUARY)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = calendar.time

        calendar.set(Calendar.MONTH, Calendar.DECEMBER)
        calendar.set(Calendar.DAY_OF_MONTH, 31)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        val end = calendar.time

        return TimeRange(start, end)
    }

    private class FenceStatisticsBuilder(
        val fenceId: String,
        val fenceName: String
    ) {
        var totalDuration: Long = 0L
        var enterCount: Int = 0

        fun addDuration(duration: Long) {
            totalDuration += duration
        }

        fun incrementEnterCount() {
            enterCount++
        }

        fun build(): FenceStatistics {
            return FenceStatistics(
                fenceId = fenceId,
                fenceName = fenceName,
                totalDurationSeconds = totalDuration,
                enterCount = enterCount,
                categoryId = null
            )
        }
    }
}
