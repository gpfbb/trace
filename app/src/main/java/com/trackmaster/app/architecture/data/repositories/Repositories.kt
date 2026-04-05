package com.trackmaster.app.architecture.data.repositories

import com.trackmaster.app.architecture.data.datasources.*
import com.trackmaster.app.architecture.domain.models.*
import java.util.Date

/**
 * 围栏仓库
 */
class GeofenceRepository(
    private val geofenceDataSource: GeofenceDataSource,
    private val categoryDataSource: CategoryDataSource
) {
    suspend fun getAllFences(): List<Geofence> {
        return geofenceDataSource.getAllFences()
    }

    suspend fun getFenceById(id: String): Geofence? {
        return geofenceDataSource.getFenceById(id)
    }

    suspend fun saveFence(fence: Geofence): String {
        return geofenceDataSource.saveFence(fence)
    }

    suspend fun deleteFence(id: String): Boolean {
        return geofenceDataSource.deleteFence(id)
    }

    suspend fun updateFence(fence: Geofence): Boolean {
        return geofenceDataSource.updateFence(fence)
    }

    suspend fun getAllCategories(): List<Category> {
        return categoryDataSource.getAllCategories()
    }

    suspend fun getCategoryById(id: String): Category? {
        return categoryDataSource.getCategoryById(id)
    }

    suspend fun saveCategory(category: Category): String {
        return categoryDataSource.saveCategory(category)
    }

    suspend fun deleteCategory(id: String): Boolean {
        return categoryDataSource.deleteCategory(id)
    }

    suspend fun updateCategory(category: Category): Boolean {
        return categoryDataSource.updateCategory(category)
    }
}

/**
 * 事件仓库
 */
class EventRepository(private val eventDataSource: EventDataSource) {
    suspend fun recordEvent(event: GeoFenceEvent) {
        eventDataSource.insertEvent(event)
    }

    suspend fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent> {
        return eventDataSource.getEventsByTimeRange(startTime, endTime)
    }

    suspend fun getEventsByFence(fenceId: String): List<GeoFenceEvent> {
        return eventDataSource.getEventsByFence(fenceId)
    }

    suspend fun deleteOldEvents(before: Date) {
        eventDataSource.deleteEventsBefore(before.time)
    }
}

/**
 * 停留记录仓库
 */
class StayRecordRepository(
    private val stayRecordDataSource: StayRecordDataSource,
    private val dailyStayDataSource: DailyStayDataSource
) {
    suspend fun saveStayRecord(record: FenceStayRecord) {
        stayRecordDataSource.insertStayRecord(record)
    }

    suspend fun getAllStayRecords(): List<FenceStayRecord> {
        return stayRecordDataSource.getAllStayRecords()
    }

    suspend fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord> {
        return stayRecordDataSource.getStayRecordsByFence(fenceId)
    }

    suspend fun getStayRecordsByTimeRange(startTime: Date, endTime: Date): List<FenceStayRecord> {
        return stayRecordDataSource.getStayRecordsByTimeRange(startTime, endTime)
    }

    suspend fun getOpenStayRecords(): List<FenceStayRecord> {
        return stayRecordDataSource.getOpenStayRecords()
    }

    suspend fun saveDailyStayRecord(record: DailyStayRecord) {
        dailyStayDataSource.saveDailyStayRecord(record)
    }

    suspend fun getDailyStayRecord(date: String, fenceId: String): DailyStayRecord? {
        return dailyStayDataSource.getDailyStayRecord(date, fenceId)
    }

    suspend fun getDailyStayRecordsByDate(date: String): List<DailyStayRecord> {
        return dailyStayDataSource.getDailyStayRecordsByDate(date)
    }

    suspend fun deleteOldRecords(before: Date) {
        stayRecordDataSource.deleteStayRecordsBefore(before.time)
    }
}

/**
 * 统计仓库
 */
class StatisticsRepository(private val dailyStayDataSource: DailyStayDataSource) {
    suspend fun getDailyStatistics(date: String): List<DailyStayRecord> {
        return dailyStayDataSource.getDailyStayRecordsByDate(date)
    }

    suspend fun getMonthlyStatistics(_year: Int, _month: Int): Map<String, Long> {
        // 实现月度统计逻辑
        TODO("Implement monthly statistics")
    }

    suspend fun getYearlyStatistics(_year: Int): Map<String, Long> {
        // 实现年度统计逻辑
        TODO("Implement yearly statistics")
    }
}

/**
 * 分段仓库
 */
class SegmentRepository(private val segmentDataSource: SegmentDataSource) {
    suspend fun saveSegment(segment: StaySegment) {
        segmentDataSource.enqueueSegment(segment)
    }

    suspend fun getOpenSegments(): List<StaySegment> {
        return segmentDataSource.getOpenSegments()
    }

    suspend fun flush() {
        segmentDataSource.flush()
    }
}