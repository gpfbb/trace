package com.trackmaster.app

import android.content.Context
import android.os.Environment
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.FenceStayRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

data class DataExportOptions(
    val exportAll: Boolean = true,
    val beforeDate: Date? = null,
    val afterDate: Date? = null
)

data class DataExportResult(
    val success: Boolean,
    val filePath: String? = null,
    val message: String,
    val exportedFences: Int = 0,
    val exportedEvents: Int = 0,
    val exportedStays: Int = 0
)

data class DataImportResult(
    val success: Boolean,
    val message: String,
    val importedFences: Int = 0,
    val importedEvents: Int = 0,
    val importedStays: Int = 0
)

class DataManager(private val context: Context) {

    private val dbHelper = (context.applicationContext as TrackMasterApplication).dbHelper
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val exportDir = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
        "TrackMaster_Exports"
    )
    
    init {
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
    }
    
    suspend fun exportData(
        options: DataExportOptions,
        onProgress: ((Int, String) -> Unit)? = null
    ): DataExportResult = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke(5, "准备导出...")
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "TrackMaster_Export_$timestamp.json"
            val exportFile = File(exportDir, fileName)
            
            onProgress?.invoke(10, "读取数据...")
            
            val allFences = dbHelper.getAllFences()
            val allEvents = dbHelper.getAllEvents()
            val allStays = dbHelper.getAllStayRecords()
            
            onProgress?.invoke(20, "过滤数据...")
            
            val filteredEvents = filterEvents(allEvents, options)
            val filteredStays = filterStays(allStays, options)
            
            onProgress?.invoke(40, "生成导出文件...")
            
            val json = JSONObject()
            json.put("version", "1.0")
            json.put("exportTime", dateTimeFormat.format(Date()))
            
            val fencesArray = JSONArray()
            allFences.forEach { fence ->
                val fenceJson = JSONObject()
                fenceJson.put("id", fence.id)
                fenceJson.put("name", fence.name)
                fenceJson.put("type", fence.type.name)
                fenceJson.put("categoryId", fence.categoryId)
                fenceJson.put("createdAt", dateTimeFormat.format(fence.createdAt))
                
                if (fence.type == GeoFenceType.CIRCLE) {
                    fenceJson.put("centerLat", fence.center?.latitude)
                    fenceJson.put("centerLng", fence.center?.longitude)
                    fenceJson.put("radius", fence.radius)
                } else {
                    val pointsArray = JSONArray()
                    fence.points?.forEach { point ->
                        val pointJson = JSONObject()
                        pointJson.put("lat", point.latitude)
                        pointJson.put("lng", point.longitude)
                        pointsArray.put(pointJson)
                    }
                    fenceJson.put("points", pointsArray)
                }
                
                fencesArray.put(fenceJson)
            }
            json.put("fences", fencesArray)
            
            onProgress?.invoke(60, "处理事件数据...")
            
            val eventsArray = JSONArray()
            filteredEvents.forEach { event ->
                val eventJson = JSONObject()
                eventJson.put("id", event.id)
                eventJson.put("fenceId", event.fenceId)
                eventJson.put("fenceName", event.fenceName)
                eventJson.put("action", event.action.name)
                eventJson.put("time", dateTimeFormat.format(event.time))
                eventJson.put("latitude", event.latitude)
                eventJson.put("longitude", event.longitude)
                eventJson.put("accuracy", event.accuracy)
                eventJson.put("durationSeconds", event.durationSeconds)
                eventsArray.put(eventJson)
            }
            json.put("events", eventsArray)
            
            onProgress?.invoke(80, "处理停留记录...")
            
            val staysArray = JSONArray()
            filteredStays.forEach { stay ->
                val stayJson = JSONObject()
                stayJson.put("fenceId", stay.fenceId)
                stayJson.put("fenceName", stay.fenceName)
                stayJson.put("enterTime", dateTimeFormat.format(stay.enterTime))
                stayJson.put("exitTime", stay.exitTime?.let { dateTimeFormat.format(it) })
                stayJson.put("durationSeconds", stay.durationSeconds)
                staysArray.put(stayJson)
            }
            json.put("stays", staysArray)
            
            onProgress?.invoke(90, "写入文件...")
            
            FileWriter(exportFile).use { writer ->
                writer.write(json.toString(2))
            }
            
            onProgress?.invoke(100, "导出完成")
            
            DataExportResult(
                success = true,
                filePath = exportFile.absolutePath,
                message = "导出成功！文件已保存至: ${exportFile.name}",
                exportedFences = allFences.size,
                exportedEvents = filteredEvents.size,
                exportedStays = filteredStays.size
            )
        } catch (e: Exception) {
            DataExportResult(
                success = false,
                message = "导出失败: ${e.message}"
            )
        }
    }
    
    suspend fun importData(filePath: String): DataImportResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext DataImportResult(
                    success = false,
                    message = "文件不存在"
                )
            }
            
            val json = JSONObject(file.readText())
            
            var importedFences = 0
            var importedEvents = 0
            var importedStays = 0
            
            val fencesArray = json.optJSONArray("fences") ?: JSONArray()
            for (i in 0 until fencesArray.length()) {
                val fenceJson = fencesArray.getJSONObject(i)
                val id = fenceJson.getString("id")
                val name = fenceJson.getString("name")
                val type = GeoFenceType.valueOf(fenceJson.getString("type"))
                val categoryId = fenceJson.optString("categoryId", Category.UNASSIGNED_ID)
                val createdAt = try {
                    dateTimeFormat.parse(fenceJson.getString("createdAt"))
                } catch (e: Exception) {
                    Date()
                }
                
                val fence = if (type == GeoFenceType.CIRCLE) {
                    GeoFence(
                        id = id,
                        name = name,
                        type = type,
                        center = GeoFencePoint(
                            fenceJson.getDouble("centerLat"),
                            fenceJson.getDouble("centerLng")
                        ),
                        radius = fenceJson.getDouble("radius").toFloat(),
                        categoryId = categoryId,
                        createdAt = createdAt
                    )
                } else {
                    val pointsArray = fenceJson.getJSONArray("points")
                    val points = mutableListOf<GeoFencePoint>()
                    for (j in 0 until pointsArray.length()) {
                        val pointJson = pointsArray.getJSONObject(j)
                        points.add(
                            GeoFencePoint(
                                pointJson.getDouble("lat"),
                                pointJson.getDouble("lng")
                            )
                        )
                    }
                    GeoFence(
                        id = id,
                        name = name,
                        type = type,
                        points = points,
                        categoryId = categoryId,
                        createdAt = createdAt
                    )
                }
                
                dbHelper.insertFence(fence)
                importedFences++
            }
            
            val eventsArray = json.optJSONArray("events") ?: JSONArray()
            for (i in 0 until eventsArray.length()) {
                val eventJson = eventsArray.getJSONObject(i)
                val time = dateTimeFormat.parse(eventJson.getString("time")) ?: Date()
                val event = GeoFenceEvent(
                    id = eventJson.optString("id", UUID.randomUUID().toString()),
                    fenceId = eventJson.getString("fenceId"),
                    fenceName = eventJson.getString("fenceName"),
                    action = GeoFenceAction.valueOf(eventJson.getString("action")),
                    time = time,
                    latitude = eventJson.getDouble("latitude"),
                    longitude = eventJson.getDouble("longitude"),
                    accuracy = eventJson.getDouble("accuracy").toFloat(),
                    durationSeconds = eventJson.optLong("durationSeconds").takeIf { it > 0 }
                )
                dbHelper.insertEvent(event)
                importedEvents++
            }
            
            val staysArray = json.optJSONArray("stays") ?: JSONArray()
            for (i in 0 until staysArray.length()) {
                val stayJson = staysArray.getJSONObject(i)
                val enterTime = dateTimeFormat.parse(stayJson.getString("enterTime")) ?: Date()
                val stay = FenceStayRecord(
                    fenceId = stayJson.getString("fenceId"),
                    fenceName = stayJson.getString("fenceName"),
                    enterTime = enterTime,
                    exitTime = stayJson.optString("exitTime").takeIf { it.isNotEmpty() }?.let { 
                        dateTimeFormat.parse(it) 
                    },
                    durationSeconds = stayJson.optLong("durationSeconds").takeIf { it > 0 }
                )
                dbHelper.insertStayRecord(stay)
                importedStays++
            }
            
            DataImportResult(
                success = true,
                message = "导入成功！",
                importedFences = importedFences,
                importedEvents = importedEvents,
                importedStays = importedStays
            )
        } catch (e: Exception) {
            DataImportResult(
                success = false,
                message = "导入失败: ${e.message}"
            )
        }
    }
    
    suspend fun deleteData(beforeDate: Date? = null): Int = withContext(Dispatchers.IO) {
        try {
            val deletedEvents = dbHelper.deleteEventsBefore(beforeDate)
            val deletedStays = dbHelper.deleteStaysBefore(beforeDate)
            deletedEvents + deletedStays
        } catch (e: Exception) {
            0
        }
    }
    
    fun getStorageInfo(): StorageInfo {
        val fences = dbHelper.getAllFences().size
        val events = dbHelper.getAllEvents().size
        val stays = dbHelper.getAllStayRecords().size
        
        val dbFile = context.getDatabasePath("geofence.db")
        val dbSize = if (dbFile.exists()) dbFile.length() else 0L
        
        return StorageInfo(
            fenceCount = fences,
            eventCount = events,
            stayCount = stays,
            databaseSize = dbSize
        )
    }
    
    fun getExportFiles(): List<File> {
        return exportDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    private fun filterEvents(events: List<GeoFenceEvent>, options: DataExportOptions): List<GeoFenceEvent> {
        return events.filter { event ->
            var include = true
            if (options.beforeDate != null) {
                include = include && event.time <= options.beforeDate
            }
            if (options.afterDate != null) {
                include = include && event.time >= options.afterDate
            }
            include
        }
    }
    
    private fun filterStays(stays: List<FenceStayRecord>, options: DataExportOptions): List<FenceStayRecord> {
        return stays.filter { stay ->
            var include = true
            if (options.beforeDate != null) {
                include = include && stay.enterTime <= options.beforeDate
            }
            if (options.afterDate != null) {
                include = include && stay.enterTime >= options.afterDate
            }
            include
        }
    }

    // ============================================================
    // 围栏 JSON 导入导出（轻量版，仅围栏定义）
    // ============================================================

    /** 导出围栏为 JSON 文件（仅围栏定义，不含事件/停留） */
    suspend fun exportFencesToJson(): String? = withContext(Dispatchers.IO) {
        try {
            val fences = dbHelper.getAllFences()
            val jsonArray = JSONArray()
            fences.forEach { fence ->
                val obj = JSONObject()
                // 不包含 id，导入时生成新 ID
                obj.put("name", fence.name)
                obj.put("type", fence.type.name)
                obj.put("categoryId", fence.categoryId)
                if (fence.type == GeoFenceType.CIRCLE) {
                    obj.put("centerLat", fence.center?.latitude)
                    obj.put("centerLng", fence.center?.longitude)
                    obj.put("radius", fence.radius)
                } else {
                    val pts = JSONArray()
                    fence.points?.forEach { p ->
                        val pj = JSONObject()
                        pj.put("lat", p.latitude)
                        pj.put("lng", p.longitude)
                        pts.put(pj)
                    }
                    obj.put("points", pts)
                }
                jsonArray.put(obj)
            }

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val file = File(exportDir, "trackmaster_fences_$dateStr.json")
            file.writeText(jsonArray.toString(2))
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** 从 JSON 文件导入围栏 */
    suspend fun importFencesFromJson(filePath: String): Int = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext 0
            val jsonArray = JSONArray(file.readText())
            var count = 0
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val type = GeoFenceType.valueOf(obj.getString("type"))
                // 生成新 ID，不使用导入的 ID
                val newId = UUID.randomUUID().toString()
                val fence = if (type == GeoFenceType.CIRCLE) {
                    GeoFence(
                        id = newId,
                        name = obj.getString("name"),
                        type = type,
                        center = GeoFencePoint(obj.getDouble("centerLat"), obj.getDouble("centerLng")),
                        radius = obj.getDouble("radius").toFloat(),
                        categoryId = obj.optString("categoryId", Category.UNASSIGNED_ID),
                    )
                } else {
                    val pts = mutableListOf<GeoFencePoint>()
                    val arr = obj.getJSONArray("points")
                    for (j in 0 until arr.length()) {
                        val pj = arr.getJSONObject(j)
                        pts.add(GeoFencePoint(pj.getDouble("lat"), pj.getDouble("lng")))
                    }
                    GeoFence(
                        id = newId,
                        name = obj.getString("name"),
                        type = type,
                        points = pts,
                        categoryId = obj.optString("categoryId", Category.UNASSIGNED_ID),
                    )
                }
                dbHelper.insertFence(fence)
                count++
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    // ============================================================
    // 停留记录导出导入（停留统计专用）
    // ============================================================

    /**
     * 导出停留记录为 JSON 文件（stays + daily_stays + events）
     * 导出后可分享给他人
     */
    suspend fun exportStatsToJson(): StatsExportResult = withContext(Dispatchers.IO) {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "trackmaster_stats_$dateStr.json"
            val exportFile = File(exportDir, fileName)

            // 读取所有数据
            val allStays = dbHelper.getAllStayRecords()
            val allDailyStays = dbHelper.getAllDailyStayRecords()
            val allEvents = dbHelper.getAllEvents()

            val json = JSONObject()
            json.put("version", 1)
            json.put("appVersion", "1.0.0")
            json.put("exportTime", dateTimeFormat.format(Date()))
            json.put("dataType", "trackmaster-stats")

            // stays 数组
            val staysArray = JSONArray()
            allStays.forEach { stay ->
                val obj = JSONObject()
                obj.put("id", stay.id ?: "")
                obj.put("fenceId", stay.fenceId)
                obj.put("fenceName", stay.fenceName)
                obj.put("enterTime", dateTimeFormat.format(stay.enterTime))
                obj.put("exitTime", stay.exitTime?.let { dateTimeFormat.format(it) } ?: "")
                obj.put("duration", stay.durationSeconds ?: 0L)
                staysArray.put(obj)
            }
            json.put("stays", staysArray)

            // dailyStays 数组
            val dailyArray = JSONArray()
            allDailyStays.forEach { daily ->
                val obj = JSONObject()
                obj.put("date", daily.date)
                obj.put("fenceId", daily.fenceId)
                obj.put("fenceName", daily.fenceName)
                obj.put("duration", daily.totalDurationSeconds)
                dailyArray.put(obj)
            }
            json.put("dailyStays", dailyArray)

            // events 数组
            val eventsArray = JSONArray()
            allEvents.forEach { event ->
                val obj = JSONObject()
                obj.put("id", event.id)
                obj.put("fenceId", event.fenceId)
                obj.put("fenceName", event.fenceName)
                obj.put("action", event.action.name)
                obj.put("timestamp", dateTimeFormat.format(event.time))
                obj.put("latitude", event.latitude)
                obj.put("longitude", event.longitude)
                obj.put("accuracy", event.accuracy)
                eventsArray.put(obj)
            }
            json.put("events", eventsArray)

            FileWriter(exportFile).use { writer ->
                writer.write(json.toString(2))
            }

            StatsExportResult(
                success = true,
                filePath = exportFile.absolutePath,
                fileName = fileName,
                message = "导出成功",
                staysCount = allStays.size,
                dailyStaysCount = allDailyStays.size,
                eventsCount = allEvents.size
            )
        } catch (e: Exception) {
            StatsExportResult(
                success = false,
                message = "导出失败: ${e.message}"
            )
        }
    }

    /**
     * 从 JSON 文件导入停留记录（合并策略）
     * - stays/events: 按 ID 去重，新的覆盖旧的
     * - dailyStays: 按 date+fenceId 累加时长
     */
    suspend fun importStatsFromJson(filePath: String): StatsImportResult = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                return@withContext StatsImportResult(
                    success = false,
                    message = "文件不存在"
                )
            }

            val json = JSONObject(file.readText())

            // 校验格式
            val version = json.optInt("version", 0)
            val dataType = json.optString("dataType", "")
            if (version == 0 || dataType != "trackmaster-stats") {
                return@withContext StatsImportResult(
                    success = false,
                    message = "文件格式不正确，不是停留记录导出文件"
                )
            }

            var importedStays = 0
            var importedDailyStays = 0
            var importedEvents = 0

            // 导入 stays（使用 upsert 策略）
            val staysArray = json.optJSONArray("stays") ?: JSONArray()
            val stayRecords = mutableListOf<FenceStayRecord>()
            for (i in 0 until staysArray.length()) {
                val obj = staysArray.getJSONObject(i)
                val id = obj.optString("id", null).takeIf { it.isNotEmpty() }
                val enterTime = try {
                    dateTimeFormat.parse(obj.getString("enterTime")) ?: Date()
                } catch (e: Exception) {
                    Date()
                }
                val exitTime = try {
                    obj.optString("exitTime", "").takeIf { it.isNotEmpty() }
                        ?.let { dateTimeFormat.parse(it) }
                } catch (e: Exception) { null }
                val duration = obj.optLong("duration", 0L).takeIf { it > 0 }

                stayRecords.add(
                    FenceStayRecord(
                        id = id,
                        fenceId = obj.getString("fenceId"),
                        fenceName = obj.getString("fenceName"),
                        enterTime = enterTime,
                        exitTime = exitTime,
                        durationSeconds = duration
                    )
                )
            }
            if (stayRecords.isNotEmpty()) {
                dbHelper.insertStayRecordsWithId(stayRecords)
                importedStays = stayRecords.size
            }

            // 导入 dailyStays（累加策略）
            val dailyArray = json.optJSONArray("dailyStays") ?: JSONArray()
            val dailyRecords = mutableListOf<DailyStayRecord>()
            for (i in 0 until dailyArray.length()) {
                val obj = dailyArray.getJSONObject(i)
                dailyRecords.add(
                    DailyStayRecord(
                        date = obj.getString("date"),
                        fenceId = obj.getString("fenceId"),
                        fenceName = obj.getString("fenceName"),
                        totalDurationSeconds = obj.getLong("duration"),
                        lastUpdated = Date()
                    )
                )
            }
            if (dailyRecords.isNotEmpty()) {
                dbHelper.upsertDailyStayRecords(dailyRecords)
                importedDailyStays = dailyRecords.size
            }

            // 导入 events（upsert 策略）
            val eventsArray = json.optJSONArray("events") ?: JSONArray()
            val eventRecords = mutableListOf<GeoFenceEvent>()
            for (i in 0 until eventsArray.length()) {
                val obj = eventsArray.getJSONObject(i)
                val timestamp = try {
                    dateTimeFormat.parse(obj.getString("timestamp")) ?: Date()
                } catch (e: Exception) {
                    Date()
                }
                eventRecords.add(
                    GeoFenceEvent(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        fenceId = obj.getString("fenceId"),
                        fenceName = obj.getString("fenceName"),
                        action = GeoFenceAction.valueOf(obj.getString("action")),
                        time = timestamp,
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        accuracy = obj.getDouble("accuracy").toFloat()
                    )
                )
            }
            if (eventRecords.isNotEmpty()) {
                dbHelper.insertEventsWithId(eventRecords)
                importedEvents = eventRecords.size
            }

            StatsImportResult(
                success = true,
                message = "导入成功",
                importedStays = importedStays,
                importedDailyStays = importedDailyStays,
                importedEvents = importedEvents
            )
        } catch (e: Exception) {
            StatsImportResult(
                success = false,
                message = "导入失败: ${e.message}"
            )
        }
    }

    /**
     * 获取分享用的文件 URI
     */
    fun getShareableStatsFile(): File? {
        val files = exportDir.listFiles()?.sortedByDescending { it.lastModified() }
        return files?.firstOrNull()
    }

    /**
     * 获取所有每日停留记录（用于统计页面）
     */
    fun getAllDailyStayRecords(): List<DailyStayRecord> {
        return dbHelper.getAllDailyStayRecords()
    }
}

data class StatsExportResult(
    val success: Boolean,
    val filePath: String? = null,
    val fileName: String? = null,
    val message: String,
    val staysCount: Int = 0,
    val dailyStaysCount: Int = 0,
    val eventsCount: Int = 0
)

data class StatsImportResult(
    val success: Boolean,
    val message: String,
    val importedStays: Int = 0,
    val importedDailyStays: Int = 0,
    val importedEvents: Int = 0
)

data class StorageInfo(
    val fenceCount: Int,
    val eventCount: Int,
    val stayCount: Int,
    val databaseSize: Long
) {
    val formattedSize: String
        get() {
            return when {
                databaseSize >= 1024 * 1024 -> "%.2f MB".format(databaseSize / (1024.0 * 1024.0))
                databaseSize >= 1024 -> "%.2f KB".format(databaseSize / 1024.0)
                else -> "$databaseSize B"
            }
        }
}
