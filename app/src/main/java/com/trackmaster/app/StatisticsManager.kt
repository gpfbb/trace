package com.trackmaster.app

import android.content.Context
import android.graphics.Color
import com.trackmaster.app.data.GeoFenceAction
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
        get() = TrackMasterApplication.instance.dbHelper

    private val app: TrackMasterApplication
        get() = TrackMasterApplication.instance

    /**
     * 获取统计数据（基于 daily_stays 精确分天 + 当前活跃会话的实时时长）
     *
     * 【修复】使用 daily_stays 表获取已完成的分天时长数据，
     * 而非 stays 表（会导致跨天停留的全部时长错误归入进入日）。
     */
    fun getStatistics(timeDimension: TimeDimension, timeRange: TimeRange): List<FenceStatistics> {
        android.util.Log.d("StatisticsManager", "getStatistics called: dimension=$timeDimension, range=${timeRange.startTime}-${timeRange.endTime}")

        // 1. 从 daily_stays 表获取已完成的分天时长数据
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val rangeStartDate = dateFormat.format(timeRange.startTime)
        val rangeEndDate = dateFormat.format(timeRange.endTime)

        // 获取时间范围内的所有 daily_stays 记录
        val allDailyRecords = when (timeDimension) {
            TimeDimension.DAY -> dbHelper.getDailyStayRecordsByDate(rangeStartDate)
            TimeDimension.MONTH, TimeDimension.YEAR -> {
                // 收集范围内所有日期的 daily_stays
                val allRecords = mutableListOf<DailyStayRecord>()
                val cal = Calendar.getInstance()
                cal.time = timeRange.startTime
                val endCal = Calendar.getInstance()
                endCal.time = timeRange.endTime
                while (!cal.after(endCal)) {
                    val dateKey = dateFormat.format(cal.time)
                    allRecords.addAll(dbHelper.getDailyStayRecordsByDate(dateKey))
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                allRecords
            }
        }
        android.util.Log.d("StatisticsManager", "Daily records: ${allDailyRecords.size}")

        val statsMap = mutableMapOf<String, FenceStatisticsBuilder>()

        // 添加已完成的分天停留记录
        for (record in allDailyRecords) {
            val fenceId = record.fenceId
            val fenceName = record.fenceName

            val builder = statsMap.getOrPut(fenceId) {
                FenceStatisticsBuilder(fenceId = fenceId, fenceName = fenceName)
            }
            builder.addDuration(record.totalDurationSeconds)
            // daily_stays 不记录进入次数，进入次数从 events 表单独统计
        }

        // 2. 从 events 表统计进入次数
        val events = dbHelper.getEventsByTimeRange(timeRange.startTime, timeRange.endTime)
        val enterCounts = events
            .filter { it.action == GeoFenceAction.ENTER }
            .groupBy { it.fenceId }
            .mapValues { it.value.size }

        // 将进入次数合并到 statsMap
        for ((fenceId, count) in enterCounts) {
            statsMap.getOrPut(fenceId) {
                FenceStatisticsBuilder(fenceId = fenceId, fenceName = fenceId)
            }.setEnterCountValue(count)
        }

        // 3. 添加当前活跃会话的实时时长（跨午夜的活跃会话只计算在时间范围内的部分）
        val activeSessions = app.stayDurationManager.activeSessions
        android.util.Log.d("StatisticsManager", "Active sessions: ${activeSessions.size}")
        val now = Date()
        for ((fenceId, session) in activeSessions) {
            val sessionEnded = false
            val overlapsWithRange = session.enterTime <= timeRange.endTime && !sessionEnded
            if (overlapsWithRange) {
                val effectiveStartTime = maxOf(session.enterTime, timeRange.startTime)
                val effectiveEndTime = minOf(now, timeRange.endTime)
                val effectiveDurationSeconds = maxOf(0L, (effectiveEndTime.time - effectiveStartTime.time) / 1000)

                android.util.Log.d("StatisticsManager", "Active session $fenceId (${session.fenceName}): duration=${effectiveDurationSeconds}s, enterTime=${session.enterTime}, effectiveRange=${effectiveStartTime}-${effectiveEndTime}")
                val builder = statsMap.getOrPut(fenceId) {
                    FenceStatisticsBuilder(fenceId = fenceId, fenceName = session.fenceName)
                }
                builder.addDuration(effectiveDurationSeconds)
                // 只有当进入时间在所选时间范围内，才计入 enterCount
                // 如果用户在时间范围之前就已进入，不应多数
                if (!enterCounts.containsKey(fenceId) &&
                    session.enterTime >= timeRange.startTime &&
                    session.enterTime <= timeRange.endTime) {
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

            var totalChildrenDirect = 0L
            for (childId in childIds) {
                val childTree = buildTree(childId, depth + 1, statsMap[childId]?.totalDurationSeconds ?: 0L)
                if (childTree != null) {
                    childTrees.add(childTree)
                    totalChildrenDirect += childTree.ownDurationSeconds
                }
            }

            // 按总时长降序排列子节点
            childTrees.sortByDescending { it.totalDurationSeconds }

            // 自身时长 + 子节点时长 = 总时长
            val ownDur = stat?.totalDurationSeconds ?: ownDuration
            val totalDur = ownDur + totalChildrenDirect

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
     * 返回 raw 秒数，格式化由 UI 层通过 stringResource 完成
     */
    fun formatDuration(seconds: Long): Long = seconds

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

        fun setEnterCountValue(count: Int) {
            enterCount = count
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
