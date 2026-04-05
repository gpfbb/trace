package com.trackmaster.app.architecture.data.datasources

import com.trackmaster.app.architecture.domain.models.*
import java.util.Date

/**
 * 围栏数据源接口
 */
interface GeofenceDataSource {
    suspend fun getAllFences(): List<Geofence>
    suspend fun getFenceById(id: String): Geofence?
    suspend fun saveFence(fence: Geofence): String
    suspend fun deleteFence(id: String): Boolean
    suspend fun updateFence(fence: Geofence): Boolean
}

/**
 * 分类数据源接口
 */
interface CategoryDataSource {
    suspend fun getAllCategories(): List<Category>
    suspend fun getCategoryById(id: String): Category?
    suspend fun saveCategory(category: Category): String
    suspend fun deleteCategory(id: String): Boolean
    suspend fun updateCategory(category: Category): Boolean
}

/**
 * 事件数据源接口
 */
interface EventDataSource {
    suspend fun insertEvent(event: GeoFenceEvent): Long
    suspend fun insertEvents(events: List<GeoFenceEvent>): Int
    suspend fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent>
    suspend fun getEventsByFence(fenceId: String): List<GeoFenceEvent>
    suspend fun deleteEventsBefore(timestamp: Long): Int
}

/**
 * 停留记录数据源接口
 */
interface StayRecordDataSource {
    suspend fun insertStayRecord(record: FenceStayRecord): Long
    suspend fun getAllStayRecords(): List<FenceStayRecord>
    suspend fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord>
    suspend fun getStayRecordsByTimeRange(startTime: Date, endTime: Date): List<FenceStayRecord>
    suspend fun getOpenStayRecords(): List<FenceStayRecord>
    suspend fun updateStayRecord(record: FenceStayRecord): Boolean
    suspend fun deleteStayRecordsBefore(timestamp: Long): Int
}

/**
 * 日统计数据源接口
 */
interface DailyStayDataSource {
    suspend fun saveDailyStayRecord(record: DailyStayRecord): Long
    suspend fun getDailyStayRecord(date: String, fenceId: String): DailyStayRecord?
    suspend fun getDailyStayRecordsByDate(date: String): List<DailyStayRecord>
    suspend fun getDailyStayRecordsByFence(fenceId: String): List<DailyStayRecord>
    suspend fun deleteDailyStayRecordsBefore(date: String): Int
}

/**
 * 停留片段数据源接口
 */
interface SegmentDataSource {
    suspend fun enqueueSegment(segment: StaySegment)
    suspend fun getOpenSegments(): List<StaySegment>
    suspend fun flush()
}