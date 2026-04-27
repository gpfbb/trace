package com.trackmaster.app.core

import com.trackmaster.app.utils.AppLog

/**
 * 空间索引接口
 * 用于加速地理围栏的空间查询
 */
interface SpatialIndex {
    /**
     * 构建索引
     * @param fences 要索引的围栏列表
     */
    fun build(fences: List<Geofence>)

    /**
     * 查询可能与给定点相交的围栏
     * @param point 查询点
     * @return 候选围栏ID列表（需要进一步精确检测）
     */
    fun query(point: GeoPoint): List<String>

    /**
     * 添加单个围栏到索引
     * @param fence 要添加的围栏
     */
    fun add(fence: Geofence)

    /**
     * 从索引中移除围栏
     * @param fenceId 要移除的围栏ID
     */
    fun remove(fenceId: String)

    /**
     * 清除索引
     */
    fun clear()

    /**
     * 获取索引统计信息
     */
    fun getStats(): SpatialIndexStats
}

/**
 * 空间索引统计信息
 */
data class SpatialIndexStats(
    val totalFences: Int,
    val gridCells: Int,
    val avgFencesPerCell: Double,
    val maxFencesPerCell: Int,
    val minFencesPerCell: Int,
    val memoryUsageBytes: Long
)

/**
 * 基于网格的空间索引实现
 * 将地理区域划分为固定大小的网格，每个网格存储bbox与网格相交的围栏
 *
 * @param gridSizeDeg 网格大小（度），默认0.01度≈1.1公里
 */
class GridSpatialIndex(private val gridSizeDeg: Double = 0.01) : SpatialIndex {
    companion object {
        private const val TAG = "GridSpatialIndex"
    }

    // 网格映射：网格键 → 围栏ID列表
    private val gridMap = mutableMapOf<String, MutableList<String>>()
    // 围栏 → bbox 映射（用于更新和移除）
    private val fenceMap = mutableMapOf<String, Geofence>()

    // 统计信息
    private var buildTimeMs: Long = 0
    private var queryCount: Int = 0
    private var totalCandidates: Int = 0

    /**
     * 计算点的网格键
     */
    private fun gridKey(lat: Double, lng: Double): String {
        val latCell = (lat / gridSizeDeg).toInt()
        val lngCell = (lng / gridSizeDeg).toInt()
        return "${latCell}_${lngCell}"
    }

    /**
     * 计算bbox覆盖的网格键列表
     */
    private fun bboxGridKeys(bbox: BBox): Set<String> {
        val keys = mutableSetOf<String>()

        // 计算bbox覆盖的网格范围
        val minLatCell = (bbox.minLat / gridSizeDeg).toInt()
        val maxLatCell = (bbox.maxLat / gridSizeDeg).toInt()
        val minLngCell = (bbox.minLng / gridSizeDeg).toInt()
        val maxLngCell = (bbox.maxLng / gridSizeDeg).toInt()

        // 遍历所有网格
        for (latCell in minLatCell..maxLatCell) {
            for (lngCell in minLngCell..maxLngCell) {
                keys.add("${latCell}_${lngCell}")
            }
        }

        return keys
    }

    override fun build(fences: List<Geofence>) {
        val startTime = System.currentTimeMillis()

        clear()

        fences.forEach { add(it) }

        buildTimeMs = System.currentTimeMillis() - startTime

        val stats = getStats()
        AppLog.d(TAG, "网格索引构建完成: 围栏=${stats.totalFences}, 网格=${stats.gridCells}, " +
                "平均每网格=${"%.2f".format(stats.avgFencesPerCell)}, " +
                "最大每网格=${stats.maxFencesPerCell}, 耗时=${buildTimeMs}ms")
    }

    override fun add(fence: Geofence) {
        val bbox = fence.bbox ?: return
        val fenceId = fence.id

        fenceMap[fenceId] = fence

        val gridKeys = bboxGridKeys(bbox)
        for (key in gridKeys) {
            val list = gridMap.getOrPut(key) { mutableListOf() }
            if (!list.contains(fenceId)) {
                list.add(fenceId)
            }
        }
    }

    override fun remove(fenceId: String) {
        val fence = fenceMap.remove(fenceId) ?: return
        val bbox = fence.bbox ?: return

        val gridKeys = bboxGridKeys(bbox)
        for (key in gridKeys) {
            gridMap[key]?.remove(fenceId)
            // 如果网格变空，移除该网格条目以节省内存
            if (gridMap[key]?.isEmpty() == true) {
                gridMap.remove(key)
            }
        }
    }

    override fun query(point: GeoPoint): List<String> {
        queryCount++

        val key = gridKey(point.lat, point.lng)
        val candidates = gridMap[key] ?: emptyList()

        totalCandidates += candidates.size

        // 每100次查询记录一次平均候选数
        if (queryCount % 100 == 0) {
            val avgCandidates = if (queryCount > 0) totalCandidates.toDouble() / queryCount else 0.0
            AppLog.d(TAG, "查询统计: 次数=$queryCount, 平均候选数=${"%.2f".format(avgCandidates)}")
        }

        return candidates
    }

    override fun clear() {
        gridMap.clear()
        fenceMap.clear()
        buildTimeMs = 0
        queryCount = 0
        totalCandidates = 0
        AppLog.d(TAG, "网格索引已清除")
    }

    override fun getStats(): SpatialIndexStats {
        if (gridMap.isEmpty()) {
            return SpatialIndexStats(
                totalFences = 0,
                gridCells = 0,
                avgFencesPerCell = 0.0,
                maxFencesPerCell = 0,
                minFencesPerCell = 0,
                memoryUsageBytes = 0
            )
        }

        val totalFences = fenceMap.size
        val gridCells = gridMap.size

        val fencesPerCell = gridMap.values.map { it.size }
        val avgFencesPerCell = fencesPerCell.average()
        val maxFencesPerCell = fencesPerCell.maxOrNull() ?: 0
        val minFencesPerCell = fencesPerCell.minOrNull() ?: 0

        // 估算内存使用（粗略估计）
        val memoryUsageBytes = (
            gridMap.size * 50 + // 每个网格键的开销
            gridMap.values.sumOf { it.size } * 20 // 每个围栏ID的开销
        ).toLong()

        return SpatialIndexStats(
            totalFences = totalFences,
            gridCells = gridCells,
            avgFencesPerCell = avgFencesPerCell,
            maxFencesPerCell = maxFencesPerCell,
            minFencesPerCell = minFencesPerCell,
            memoryUsageBytes = memoryUsageBytes
        )
    }

    /**
     * 获取性能统计
     */
    data class PerformanceStats(
        val buildTimeMs: Long,
        val queryCount: Int,
        val totalCandidates: Int,
        val avgCandidatesPerQuery: Double
    )

    fun getPerformanceStats(): PerformanceStats {
        val avgCandidates = if (queryCount > 0) totalCandidates.toDouble() / queryCount else 0.0
        return PerformanceStats(
            buildTimeMs = buildTimeMs,
            queryCount = queryCount,
            totalCandidates = totalCandidates,
            avgCandidatesPerQuery = avgCandidates
        )
    }
}

/**
 * 全局空间索引实例
 */
object SpatialIndexManager {
    private var index: SpatialIndex? = null

    /**
     * 初始化空间索引
     */
    fun initialize(fences: List<Geofence>, gridSizeDeg: Double = 0.01): SpatialIndex {
        val newIndex = GridSpatialIndex(gridSizeDeg)
        newIndex.build(fences)
        index = newIndex
        return newIndex
    }

    /**
     * 获取当前索引（如果已初始化）
     */
    fun getIndex(): SpatialIndex? = index

    /**
     * 清除索引
     */
    fun clear() {
        index?.clear()
        index = null
    }
}