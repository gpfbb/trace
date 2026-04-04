package com.trackmaster.app

import android.content.Context
import android.os.Environment
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
    
    private val dbHelper = GeoFenceDatabaseHelper(context)
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
                val event = GeoFenceEvent(
                    id = eventJson.optString("id", UUID.randomUUID().toString()),
                    fenceId = eventJson.getString("fenceId"),
                    fenceName = eventJson.getString("fenceName"),
                    action = GeoFenceAction.valueOf(eventJson.getString("action")),
                    time = dateTimeFormat.parse(eventJson.getString("time"))!!,
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
                val stay = FenceStayRecord(
                    fenceId = stayJson.getString("fenceId"),
                    fenceName = stayJson.getString("fenceName"),
                    enterTime = dateTimeFormat.parse(stayJson.getString("enterTime"))!!,
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
        
        val dbFile = context.getDatabasePath("geofences.db")
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
}

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
