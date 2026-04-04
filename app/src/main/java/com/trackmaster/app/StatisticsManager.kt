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
    
    private val dbHelper = GeoFenceDatabaseHelper(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private val cache = mutableMapOf<String, List<FenceStatistics>>()
    private val cacheTime = mutableMapOf<String, Long>()
    private val CACHE_DURATION = 5 * 60 * 1000L
    
    suspend fun getStatistics(
        timeDimension: TimeDimension,
        timeRange: TimeRange
    ): List<FenceStatistics> = withContext(Dispatchers.IO) {
        val cacheKey = "${timeDimension.name}_${timeRange.startTime.time}_${timeRange.endTime.time}"
        
        if (cache.containsKey(cacheKey)) {
            val lastUpdate = cacheTime[cacheKey] ?: 0
            if (System.currentTimeMillis() - lastUpdate < CACHE_DURATION) {
                return@withContext cache[cacheKey]!!
            }
        }
        
        val allStays = dbHelper.getAllStayRecords()
        val fences = dbHelper.getAllFences()
        
        val fenceMap = fences.associateBy { it.id }
        
        val filteredStays = allStays.filter { stay ->
            stay.enterTime >= timeRange.startTime && 
            (stay.exitTime == null || stay.exitTime <= timeRange.endTime)
        }
        
        val stats = filteredStays
            .groupBy { it.fenceId }
            .map { (fenceId, stays) ->
                val fence = fenceMap[fenceId]
                val totalDuration = stays.sumOf { it.durationSeconds ?: 0L }
                FenceStatistics(
                    fenceId = fenceId,
                    fenceName = fence?.name ?: stays.firstOrNull()?.fenceName ?: "未知",
                    totalDurationSeconds = totalDuration,
                    enterCount = stays.size,
                    categoryId = fence?.categoryId
                )
            }
            .sortedByDescending { it.totalDurationSeconds }
        
        cache[cacheKey] = stats
        cacheTime[cacheKey] = System.currentTimeMillis()
        
        stats
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
    
    fun clearCache() {
        cache.clear()
        cacheTime.clear()
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
