package com.trackmaster.app.architecture.domain.usecases

import com.trackmaster.app.architecture.data.repositories.*
import com.trackmaster.app.architecture.domain.models.*
import java.util.Calendar
import java.util.Date

/**
 * 围栏管理用例
 */
class GeofenceManagementUseCase(private val geofenceRepository: GeofenceRepository) {
    suspend fun getAllFences(): List<Geofence> {
        return geofenceRepository.getAllFences()
    }

    suspend fun getFenceById(id: String): Geofence? {
        return geofenceRepository.getFenceById(id)
    }

    suspend fun createFence(fence: Geofence): String {
        return geofenceRepository.saveFence(fence)
    }

    suspend fun updateFence(fence: Geofence): Boolean {
        return geofenceRepository.updateFence(fence)
    }

    suspend fun deleteFence(id: String): Boolean {
        return geofenceRepository.deleteFence(id)
    }

    suspend fun getAllCategories(): List<Category> {
        return geofenceRepository.getAllCategories()
    }

    suspend fun createCategory(category: Category): String {
        return geofenceRepository.saveCategory(category)
    }

    suspend fun updateCategory(category: Category): Boolean {
        return geofenceRepository.updateCategory(category)
    }

    suspend fun deleteCategory(id: String): Boolean {
        return geofenceRepository.deleteCategory(id)
    }
}

/**
 * 位置处理用例
 */
class LocationProcessingUseCase(
    private val geofenceRepository: GeofenceRepository,
    private val eventRepository: EventRepository,
    private val stayRecordRepository: StayRecordRepository,
    private val segmentRepository: SegmentRepository
) {
    suspend fun processLocationUpdate(fix: LocationFix): StaySegment? {
        // 1. 获取所有围栏
        val fences = geofenceRepository.getAllFences()
        
        // 2. 找到所有命中的围栏（按面积排序）
        val matchedFences = findMatchedFences(fix, fences)
        
        // 3. 处理围栏事件
        if (matchedFences.isNotEmpty()) {
            // 记录进入事件
            val smallestFence = matchedFences.first()
            val event = GeoFenceEvent(
                fenceId = smallestFence.id,
                fenceName = smallestFence.name,
                action = GeoFenceAction.ENTER,
                time = Date(fix.timestamp),
                latitude = fix.lat,
                longitude = fix.lng,
                accuracy = fix.accuracy
            )
            eventRepository.recordEvent(event)
        }
        
        // 4. 生成停留片段
        return null // 简化实现，实际逻辑需要与引擎集成
    }

    private fun findMatchedFences(_fix: LocationFix, _fences: List<Geofence>): List<Geofence> {
        // 实现围栏匹配逻辑
        TODO("Implement fence matching logic")
    }
}

/**
 * 停留时长管理用例
 */
class StayDurationUseCase(private val stayRecordRepository: StayRecordRepository) {
    suspend fun onFenceEnter(_fenceId: String, _fenceName: String, _enterTime: Date) {
        // 开始计时逻辑
    }

    suspend fun onFenceExit(fenceId: String, fenceName: String, exitTime: Date) {
        // 结束计时并保存记录
        val duration = (exitTime.time - exitTime.time) / 1000
        if (duration > 0) {
            val stayRecord = FenceStayRecord(
                fenceId = fenceId,
                fenceName = fenceName,
                enterTime = exitTime,
                exitTime = exitTime,
                durationSeconds = duration
            )
            stayRecordRepository.saveStayRecord(stayRecord)
        }
    }

    suspend fun getDailyDuration(date: String, fenceId: String): Long {
        val record = stayRecordRepository.getDailyStayRecord(date, fenceId)
        return record?.totalDurationSeconds ?: 0
    }

    suspend fun getTotalDailyDuration(date: String): Long {
        val records = stayRecordRepository.getDailyStayRecordsByDate(date)
        return records.sumOf { it.totalDurationSeconds }
    }
}

/**
 * 统计分析用例
 */
class StatisticsUseCase(private val statisticsRepository: StatisticsRepository) {
    suspend fun getDailyStatistics(date: String): List<DailyStayRecord> {
        return statisticsRepository.getDailyStatistics(date)
    }

    suspend fun getMonthlyStatistics(year: Int, month: Int): Map<String, Long> {
        return statisticsRepository.getMonthlyStatistics(year, month)
    }

    suspend fun getYearlyStatistics(year: Int): Map<String, Long> {
        return statisticsRepository.getYearlyStatistics(year)
    }

    suspend fun getStatisticsByTimeRange(_startTime: Date, _endTime: Date): List<DailyStayRecord> {
        // 实现时间范围统计逻辑
        TODO("Implement time range statistics")
    }
}

/**
 * 数据管理用例
 */
class DataManagementUseCase(
    private val eventRepository: EventRepository,
    private val stayRecordRepository: StayRecordRepository
) {
    suspend fun exportData(): String {
        // 实现数据导出逻辑
        TODO("Implement data export")
    }

    suspend fun importData(_data: String): Boolean {
        // 实现数据导入逻辑
        TODO("Implement data import")
    }

    suspend fun deleteOldData(daysToKeep: Int) {
        val cutoffDate = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysToKeep)
        }.time
        
        eventRepository.deleteOldEvents(cutoffDate)
        stayRecordRepository.deleteOldRecords(cutoffDate)
    }
}