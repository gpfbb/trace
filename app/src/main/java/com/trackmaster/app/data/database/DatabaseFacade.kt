package com.trackmaster.app.data.database

import android.content.Context
import com.trackmaster.app.DailyStayRecord
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.data.DailyFenceStats
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.dao.CategoryDao
import com.trackmaster.app.data.dao.DailyStayDao
import com.trackmaster.app.data.dao.EventDao
import com.trackmaster.app.data.dao.FenceDao
import com.trackmaster.app.data.dao.StayDao
import com.trackmaster.app.model.Category
import java.util.Date

/**
 * 数据库门面 — 将旧 GeoFenceDatabaseHelper 的 API 委托给拆分后的 DAO。
 * 在 Phase 6 完成 ViewModel DI 解耦后，这个门面可以简化/移除。
 */
class DatabaseFacade(
    private val dbManager: DatabaseManager,
    val fenceDao: FenceDao,
    val categoryDao: CategoryDao,
    val eventDao: EventDao,
    val stayDao: StayDao,
    val dailyStayDao: DailyStayDao,
    val fenceExporter: FenceExporter
) {
    // ============================================================
    // 围栏操作
    // ============================================================

    fun insertFence(fence: GeoFence): Long = fenceDao.insertFence(fence)
    fun getAllFences(): List<GeoFence> = fenceDao.getAllFences()
    fun getFenceById(fenceId: String): GeoFence? = fenceDao.getFenceById(fenceId)
    fun updateFence(fence: GeoFence): Int = fenceDao.updateFence(fence)
    fun deleteFence(fenceId: String): Int = fenceDao.deleteFence(fenceId)
    fun deleteAllFences(): Int = fenceDao.deleteAllFences()
    fun getFencesByCategory(categoryId: String): List<GeoFence> = fenceDao.getFencesByCategory(categoryId)
    fun moveFencesToCategory(fromCategoryId: String, toCategoryId: String): Int = fenceDao.moveFencesToCategory(fromCategoryId, toCategoryId)
    fun updateFenceCategory(fenceId: String, categoryId: String): Int = fenceDao.updateFenceCategory(fenceId, categoryId)
    fun updateAllFencesAlpha(alpha: Float): Int = fenceDao.updateAllFencesAlpha(alpha)

    // ============================================================
    // 分类操作
    // ============================================================

    fun insertCategory(category: Category): Long = categoryDao.insertCategory(category)
    fun getAllCategories(): List<Category> = categoryDao.getAllCategories()
    fun getCategoryByName(name: String): Category? = categoryDao.getCategoryByName(name)
    fun getCategoryById(id: String): Category? = categoryDao.getCategoryById(id)
    fun updateCategory(category: Category): Int = categoryDao.updateCategory(category)
    fun deleteCategory(categoryId: String): Int = categoryDao.deleteCategory(categoryId)

    // ============================================================
    // 事件操作
    // ============================================================

    fun insertEvent(event: GeoFenceEvent): Long = eventDao.insertEvent(event)
    fun insertEvents(events: List<GeoFenceEvent>): Int = eventDao.insertEvents(events)
    fun insertEventsWithId(events: List<GeoFenceEvent>) = eventDao.insertEventsWithId(events)
    fun getEventsByFence(fenceId: String, limit: Int = 100): List<GeoFenceEvent> = eventDao.getEventsByFence(fenceId, limit)
    fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent> = eventDao.getEventsByTimeRange(startTime, endTime)
    fun countEnterEventsByFence(startTime: Date, endTime: Date): Map<String, Int> =
        eventDao.countEnterEventsByFence(startTime, endTime)
    fun getAllEvents(limit: Int = 10000): List<GeoFenceEvent> = eventDao.getAllEvents(limit)
    fun deleteEventsBefore(beforeDate: Date?): Int = eventDao.deleteEventsBefore(beforeDate)

    // ============================================================
    // 停留记录操作
    // ============================================================

    fun insertStayRecord(record: FenceStayRecord): Long = stayDao.insertStayRecord(record)
    fun insertStayRecordsWithId(records: List<FenceStayRecord>) = stayDao.insertStayRecordsWithId(records)
    fun updateOpenStayAccumulatedDuration(fenceId: String, accumulatedDuration: Long): Int = stayDao.updateOpenStayAccumulatedDuration(fenceId, accumulatedDuration)
    fun closeOpenStayRecord(fenceId: String, exitTime: Date, durationSeconds: Long): Int = stayDao.closeOpenStayRecord(fenceId, exitTime, durationSeconds)
    fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord> = stayDao.getStayRecordsByFence(fenceId)
    fun getAllStayRecords(): List<FenceStayRecord> = stayDao.getAllStayRecords()
    fun getStayRecordsByTimeRange(startTime: Date, endTime: Date): List<FenceStayRecord> = stayDao.getStayRecordsByTimeRange(startTime, endTime)
    fun getOpenStayRecords(): List<FenceStayRecord> = stayDao.getOpenStayRecords()
    fun deleteStaysBefore(beforeDate: Date?): Int = stayDao.deleteStaysBefore(beforeDate)
    fun getFenceStats(fenceId: String): Long = stayDao.getFenceStats(fenceId)
    fun getDailyStats(date: String): Map<String, Long> = stayDao.getDailyStats(date)
    fun getDailyStatsByFenceAndDate(fenceId: String, date: String): DailyFenceStats? = stayDao.getDailyStatsByFenceAndDate(fenceId, date)

    // ============================================================
    // 每日停留操作
    // ============================================================

    fun saveDailyStayRecord(record: DailyStayRecord) = dailyStayDao.saveDailyStayRecord(record)
    fun getDailyStayRecord(dateKey: String, fenceId: String): DailyStayRecord? = dailyStayDao.getDailyStayRecord(dateKey, fenceId)
    fun getDailyStayRecordsByDate(dateKey: String): List<DailyStayRecord> = dailyStayDao.getDailyStayRecordsByDate(dateKey)
    fun getDailyStayRecordsByDateRange(startDateKey: String, endDateKey: String): List<DailyStayRecord> =
        dailyStayDao.getDailyStayRecordsByDateRange(startDateKey, endDateKey)
    fun getAllDailyStayRecords(): List<DailyStayRecord> = dailyStayDao.getAllDailyStayRecords()
    fun upsertDailyStayRecords(records: List<DailyStayRecord>) = dailyStayDao.upsertDailyStayRecords(records)

    // ============================================================
    // 导出/导入
    // ============================================================

    fun exportToCSV(context: Context): String? = fenceExporter.exportToCSV(context)
    fun generateFencesJson(fences: List<GeoFence>): String? = fenceExporter.generateFencesJson(fences)
    fun exportFencesToJson(fences: List<GeoFence>, context: Context): String? = fenceExporter.exportFencesToJson(fences, context)
    fun importFencesFromJson(jsonContent: String, preserveId: Boolean = false): Int = fenceExporter.importFencesFromJson(jsonContent, fenceDao, preserveId)
}
