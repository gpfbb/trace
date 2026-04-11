package com.trackmaster.app

import android.content.Context
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

data class TimeRange(
    val startTime: Date,
    val endTime: Date
)

enum class TimeDimension {
    DAY,
    MONTH,
    YEAR
}

data class ChartData(
    val label: String,
    val value: Long,
    val color: androidx.compose.ui.graphics.Color
)

class StatisticsManager(private val context: Context) {

    private val dbHelper = (context.applicationContext as TrackMasterApplication).dbHelper
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val app = context.applicationContext as? TrackMasterApplication
    
    suspend fun getStatistics(
        timeDimension: TimeDimension,
        timeRange: TimeRange
    ): List<FenceStatistics> = withContext(Dispatchers.IO) {
        val stayDurationManager = app?.stayDurationManager
        val fences = dbHelper.getAllFences()
        val fenceMap = fences.associateBy { it.id }
        
        val statsMap = mutableMapOf<String, FenceStatisticsBuilder>()
        
        when (timeDimension) {
            TimeDimension.DAY -> {
                val dateKey = dateFormat.format(timeRange.startTime)
                val dailyRecords = stayDurationManager?.getAllDailyRecords(dateKey) ?: emptyList()
                
                for (record in dailyRecords) {
                    val builder = statsMap.getOrPut(record.fenceId) {
                        FenceStatisticsBuilder(
                            fenceId = record.fenceId,
                            fenceName = record.fenceName,
                            categoryId = fenceMap[record.fenceId]?.categoryId
                        )
                    }
                    builder.totalDuration += record.totalDurationSeconds
                    builder.enterCount += 1
                }
                
                val activeSessions = stayDurationManager?.getActiveSessions() ?: emptyList()
                for (session in activeSessions) {
                    if (session.dateKey == dateKey) {
                        val currentDuration = stayDurationManager?.getCurrentDuration(session.fenceId) ?: 0L
                        val builder = statsMap.getOrPut(session.fenceId) {
                            FenceStatisticsBuilder(
                                fenceId = session.fenceId,
                                fenceName = session.fenceName,
                                categoryId = fenceMap[session.fenceId]?.categoryId
                            )
                        }
                        builder.totalDuration += currentDuration
                    }
                }
            }
            TimeDimension.MONTH -> {
                val calendar = Calendar.getInstance()
                calendar.time = timeRange.startTime
                val year = calendar.get(Calendar.YEAR)
                val month = calendar.get(Calendar.MONTH)
                val parentMap = app?.fenceTreeManager?.getParentMap() ?: emptyMap()
                val monthlyRecords = stayDurationManager?.getMonthlyRecordsWithAggregation(year, month, parentMap) ?: emptyMap()

                for ((fenceId, duration) in monthlyRecords) {
                    val fence = fenceMap[fenceId]
                    val builder = statsMap.getOrPut(fenceId) {
                        FenceStatisticsBuilder(
                            fenceId = fenceId,
                            fenceName = fence?.name ?: fenceId,
                            categoryId = fence?.categoryId
                        )
                    }
                    builder.totalDuration += duration
                    builder.enterCount += 1
                }
            }
            TimeDimension.YEAR -> {
                val calendar = Calendar.getInstance()
                calendar.time = timeRange.startTime
                val year = calendar.get(Calendar.YEAR)
                val parentMap = app?.fenceTreeManager?.getParentMap() ?: emptyMap()
                val yearlyRecords = stayDurationManager?.getYearlyRecordsWithAggregation(year, parentMap) ?: emptyMap()

                for ((fenceId, duration) in yearlyRecords) {
                    val fence = fenceMap[fenceId]
                    val builder = statsMap.getOrPut(fenceId) {
                        FenceStatisticsBuilder(
                            fenceId = fenceId,
                            fenceName = fence?.name ?: fenceId,
                            categoryId = fence?.categoryId
                        )
                    }
                    builder.totalDuration += duration
                    builder.enterCount += 1
                }
            }
        }
        
        statsMap.values
            .map { it.build() }
            .sortedByDescending { it.totalDurationSeconds }
    }
    
    fun getTodayRange(): TimeRange {
        val calendar = Calendar.getInstance()
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
    
    fun getMonthRange(): TimeRange {
        val calendar = Calendar.getInstance()
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
    
    fun getYearRange(): TimeRange {
        val calendar = Calendar.getInstance()
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
    
    fun getDailyRange(date: Date): TimeRange {
        val calendar = Calendar.getInstance()
        calendar.time = date
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
    
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format("%d小时%d分%d秒", hours, minutes, secs)
        } else if (minutes > 0) {
            String.format("%d分%d秒", minutes, secs)
        } else {
            String.format("%d秒", secs)
        }
    }
}

private class FenceStatisticsBuilder(
    val fenceId: String,
    val fenceName: String,
    val categoryId: String?
) {
    var totalDuration: Long = 0L
    var enterCount: Int = 0
    
    fun build(): FenceStatistics {
        return FenceStatistics(
            fenceId = fenceId,
            fenceName = fenceName,
            totalDurationSeconds = totalDuration,
            enterCount = enterCount,
            categoryId = categoryId
        )
    }
}
