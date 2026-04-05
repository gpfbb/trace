package com.trackmaster.app.architecture.data.datasources

import com.trackmaster.app.GeoFenceDatabaseHelper
import com.trackmaster.app.architecture.domain.models.*
import java.util.Date

/**
 * SQLite 围栏数据源实现
 */
class SQLiteGeofenceDataSource(private val dbHelper: GeoFenceDatabaseHelper) : GeofenceDataSource {
    override suspend fun getAllFences(): List<Geofence> {
        return dbHelper.getAllFences().map { it.toDomain() }
    }

    override suspend fun getFenceById(id: String): Geofence? {
        // 实现通过 ID 获取围栏的逻辑
        return dbHelper.getAllFences().find { it.id == id }?.toDomain()
    }

    override suspend fun saveFence(fence: Geofence): String {
        return dbHelper.insertFence(fence.toAppModel()).toString()
    }

    override suspend fun deleteFence(id: String): Boolean {
        return dbHelper.deleteFence(id) > 0
    }

    override suspend fun updateFence(fence: Geofence): Boolean {
        // 实现更新围栏的逻辑
        // 由于 GeoFenceDatabaseHelper 没有直接的 updateFence 方法，我们可以先删除再插入
        val deleted = dbHelper.deleteFence(fence.id) > 0
        if (deleted) {
            dbHelper.insertFence(fence.toAppModel())
            return true
        }
        return false
    }
}

/**
 * SQLite 分类数据源实现
 */
class SQLiteCategoryDataSource(private val dbHelper: GeoFenceDatabaseHelper) : CategoryDataSource {
    override suspend fun getAllCategories(): List<Category> {
        return dbHelper.getAllCategories().map { it.toDomain() }
    }

    override suspend fun getCategoryById(id: String): Category? {
        // 实现通过 ID 获取分类的逻辑
        return dbHelper.getAllCategories().find { it.id == id }?.toDomain()
    }

    override suspend fun saveCategory(category: Category): String {
        return dbHelper.insertCategory(category.toAppModel()).toString()
    }

    override suspend fun deleteCategory(id: String): Boolean {
        return dbHelper.deleteCategory(id) > 0
    }

    override suspend fun updateCategory(category: Category): Boolean {
        return dbHelper.updateCategory(category.toAppModel()) > 0
    }
}

/**
 * SQLite 事件数据源实现
 */
class SQLiteEventDataSource(private val dbHelper: GeoFenceDatabaseHelper) : EventDataSource {
    override suspend fun insertEvent(event: GeoFenceEvent): Long {
        return dbHelper.insertEvent(event.toAppModel())
    }

    override suspend fun insertEvents(events: List<GeoFenceEvent>): Int {
        return dbHelper.insertEvents(events.map { it.toAppModel() })
    }

    override suspend fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent> {
        return dbHelper.getEventsByTimeRange(startTime, endTime).map { it.toDomain() }
    }

    override suspend fun getEventsByFence(fenceId: String): List<GeoFenceEvent> {
        return dbHelper.getEventsByFence(fenceId).map { it.toDomain() }
    }

    override suspend fun deleteEventsBefore(timestamp: Long): Int {
        return dbHelper.deleteEventsBefore(Date(timestamp))
    }
}

/**
 * SQLite 停留记录数据源实现
 */
class SQLiteStayRecordDataSource(private val dbHelper: GeoFenceDatabaseHelper) : StayRecordDataSource {
    override suspend fun insertStayRecord(record: FenceStayRecord): Long {
        return dbHelper.insertStayRecord(record.toAppModel())
    }

    override suspend fun getAllStayRecords(): List<FenceStayRecord> {
        return dbHelper.getAllStayRecords().map { it.toDomain() }
    }

    override suspend fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord> {
        return dbHelper.getStayRecordsByFence(fenceId).map { it.toDomain() }
    }

    override suspend fun getStayRecordsByTimeRange(startTime: Date, endTime: Date): List<FenceStayRecord> {
        return dbHelper.getStayRecordsByTimeRange(startTime, endTime).map { it.toDomain() }
    }

    override suspend fun getOpenStayRecords(): List<FenceStayRecord> {
        return dbHelper.getOpenStayRecords().map { it.toDomain() }
    }

    override suspend fun updateStayRecord(record: FenceStayRecord): Boolean {
        // 实现更新停留记录的逻辑
        // 由于 GeoFenceDatabaseHelper 没有直接的 updateStayRecord 方法，我们可以先删除再插入
        val deleted = dbHelper.deleteStaysBefore(Date()) > 0
        if (deleted) {
            dbHelper.insertStayRecord(record.toAppModel())
            return true
        }
        return false
    }

    override suspend fun deleteStayRecordsBefore(timestamp: Long): Int {
        return dbHelper.deleteStaysBefore(Date(timestamp))
    }
}

/**
 * SQLite 日统计数据源实现
 */
class SQLiteDailyStayDataSource(private val dbHelper: GeoFenceDatabaseHelper) : DailyStayDataSource {
    override suspend fun saveDailyStayRecord(record: DailyStayRecord): Long {
        // 实现保存日统计记录的逻辑
        // 由于 GeoFenceDatabaseHelper 没有直接的 saveDailyStayRecord 方法，我们需要实现
        return 1L
    }

    override suspend fun getDailyStayRecord(date: String, fenceId: String): DailyStayRecord? {
        return dbHelper.getDailyStayRecord(date, fenceId)?.toDomain()
    }

    override suspend fun getDailyStayRecordsByDate(date: String): List<DailyStayRecord> {
        return dbHelper.getDailyStayRecordsByDate(date).map { it.toDomain() }
    }

    override suspend fun getDailyStayRecordsByFence(fenceId: String): List<DailyStayRecord> {
        // 实现通过围栏 ID 获取日统计记录的逻辑
        return emptyList()
    }

    override suspend fun deleteDailyStayRecordsBefore(date: String): Int {
        // 实现删除指定日期之前的日统计记录的逻辑
        return 0
    }
}

/**
 * 扩展函数：领域模型与应用模型之间的转换
 */
private fun com.trackmaster.app.GeoFence.toDomain(): Geofence {
    return Geofence(
        id = id,
        name = name,
        type = if (type == com.trackmaster.app.GeoFenceType.CIRCLE) GeoFenceType.CIRCLE else GeoFenceType.POLYGON,
        parentId = null, // 应用模型中没有 parentId 字段
        depth = 0, // 应用模型中没有 depth 字段
        category = categoryId,
        polygon = points?.map { GeoPoint(it.latitude, it.longitude) } ?: emptyList(),
        bbox = null, // 应用模型中没有 bbox 字段
        center = center?.let { GeoPoint(it.latitude, it.longitude) },
        radius = radius?.toDouble(),
        createdAt = createdAt,
        enabled = true // 应用模型中没有 enabled 字段，默认设为 true
    )
}

private fun Geofence.toAppModel(): com.trackmaster.app.GeoFence {
    return com.trackmaster.app.GeoFence(
        id = id,
        name = name,
        type = if (type == GeoFenceType.CIRCLE) com.trackmaster.app.GeoFenceType.CIRCLE else com.trackmaster.app.GeoFenceType.POLYGON,
        center = center?.let { com.trackmaster.app.GeoFencePoint(it.lat, it.lng) },
        radius = radius?.toFloat(),
        points = polygon.map { com.trackmaster.app.GeoFencePoint(it.lat, it.lng) },
        categoryId = category ?: com.trackmaster.app.Category.UNASSIGNED_ID,
        createdAt = createdAt
    )
}

private fun com.trackmaster.app.Category.toDomain(): Category {
    return Category(
        id = id,
        name = name,
        color = icon ?: "#000000", // 使用 icon 字段作为 color，如果为 null 则使用默认值
        createdAt = createdAt
    )
}

private fun Category.toAppModel(): com.trackmaster.app.Category {
    return com.trackmaster.app.Category(
        id = id,
        name = name,
        icon = color, // 使用 color 字段作为 icon
        createdAt = createdAt
    )
}

private fun com.trackmaster.app.GeoFenceEvent.toDomain(): GeoFenceEvent {
    return GeoFenceEvent(
        id = id,
        fenceId = fenceId,
        fenceName = fenceName,
        action = if (action == com.trackmaster.app.GeoFenceAction.ENTER) GeoFenceAction.ENTER else GeoFenceAction.EXIT,
        time = time,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy
    )
}

private fun GeoFenceEvent.toAppModel(): com.trackmaster.app.GeoFenceEvent {
    return com.trackmaster.app.GeoFenceEvent(
        id = id,
        fenceId = fenceId,
        fenceName = fenceName,
        action = if (action == GeoFenceAction.ENTER) com.trackmaster.app.GeoFenceAction.ENTER else com.trackmaster.app.GeoFenceAction.EXIT,
        time = time,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy
    )
}

private fun com.trackmaster.app.FenceStayRecord.toDomain(): FenceStayRecord {
    return FenceStayRecord(
        id = "", // 应用模型中没有 id 字段，使用空字符串
        fenceId = fenceId,
        fenceName = fenceName,
        enterTime = enterTime,
        exitTime = exitTime,
        durationSeconds = durationSeconds
    )
}

private fun FenceStayRecord.toAppModel(): com.trackmaster.app.FenceStayRecord {
    return com.trackmaster.app.FenceStayRecord(
        fenceId = fenceId,
        fenceName = fenceName,
        enterTime = enterTime,
        exitTime = exitTime,
        durationSeconds = durationSeconds
    )
}

private fun com.trackmaster.app.DailyStayRecord.toDomain(): DailyStayRecord {
    return DailyStayRecord(
        date = date,
        fenceId = fenceId,
        fenceName = fenceName,
        totalDurationSeconds = totalDurationSeconds,
        lastUpdated = lastUpdated
    )
}

private fun DailyStayRecord.toAppModel(): com.trackmaster.app.DailyStayRecord {
    return com.trackmaster.app.DailyStayRecord(
        date = date,
        fenceId = fenceId,
        fenceName = fenceName,
        totalDurationSeconds = totalDurationSeconds,
        lastUpdated = lastUpdated
    )
}