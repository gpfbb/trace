package com.trackmaster.app.data.database

import android.content.Context
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.model.Category
import com.trackmaster.app.utils.AppLog
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class FenceExporter(private val dbManager: DatabaseManager) {

    companion object {
        private const val TAG = "FenceExporter"
    }

    /**
     * 导出所有数据到CSV文件
     */
    fun exportToCSV(context: Context): String? {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val csvContent = StringBuilder()

            csvContent.append("=== 围栏数据 ===\n")
            csvContent.append("ID,名称,类型,创建时间\n")
            // 注意：这里需要访问 DAO，暂时通过 dbManager 直接查询
            getAllFencesInternal().forEach { fence ->
                csvContent.append("${fence.id},${fence.name},${fence.type},${dateFormat.format(fence.createdAt)}\n")
            }

            csvContent.append("\n=== 事件记录 ===\n")
            csvContent.append("事件ID,围栏ID,围栏名称,类型,时间,纬度,经度,精度\n")
            // CSV 导出保持简单，直接查询
            val eventCursor = dbManager.readableDatabase.query(
                DatabaseManager.TABLE_EVENTS, null, null, null, null, null,
                "${DatabaseManager.KEY_TIMESTAMP} DESC", "10000"
            )
            try {
                if (eventCursor.moveToFirst()) {
                    do {
                        csvContent.append("${eventCursor.getString(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_EVENT_ID))},${eventCursor.getString(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID))},${eventCursor.getString(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_NAME))},${eventCursor.getString(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_ACTION))},${dateFormat.format(Date(eventCursor.getLong(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_TIMESTAMP))))},${eventCursor.getDouble(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_LATITUDE))},${eventCursor.getDouble(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_LONGITUDE))},${eventCursor.getFloat(eventCursor.getColumnIndexOrThrow(DatabaseManager.KEY_ACCURACY))}\n")
                    } while (eventCursor.moveToNext())
                }
            } finally {
                eventCursor.close()
            }

            csvContent.append("\n=== 停留记录 ===\n")
            csvContent.append("围栏ID,围栏名称,进入时间,离开时间,停留时长(秒)\n")
            val stayCursor = dbManager.readableDatabase.query(
                DatabaseManager.TABLE_STAYS, null, null, null, null, null,
                "${DatabaseManager.KEY_ENTER_TIME} DESC"
            )
            try {
                if (stayCursor.moveToFirst()) {
                    do {
                        val exitTime = if (!stayCursor.isNull(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME)))
                            dateFormat.format(Date(stayCursor.getLong(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME)))) else ""
                        val duration = if (!stayCursor.isNull(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION)))
                            stayCursor.getLong(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION)).toString() else ""
                        csvContent.append("${stayCursor.getString(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID))},${stayCursor.getString(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_NAME))},${dateFormat.format(Date(stayCursor.getLong(stayCursor.getColumnIndexOrThrow(DatabaseManager.KEY_ENTER_TIME))))},$exitTime,$duration\n")
                    } while (stayCursor.moveToNext())
                }
            } finally {
                stayCursor.close()
            }

            val fileName = "GeoFenceExport_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val file = java.io.File(context.getExternalFilesDir(null), fileName)
            file.writeText(csvContent.toString())

            AppLog.d(TAG, "CSV导出成功: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            AppLog.e(TAG, "CSV导出失败", e)
            return null
        }
    }

    /**
     * 生成围栏 JSON（用于导出/分享模板）
     */
    fun generateFencesJson(fences: List<GeoFence>): String? {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val jsonArray = JSONArray()

            fences.forEach { fence ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", UUID.randomUUID().toString())
                    put("name", fence.name)
                    put("type", fence.type.name)
                    put("color", fence.color)
                    put("alpha", fence.alpha.toDouble())
                    put("categoryId", fence.categoryId)
                    put("createdAt", dateFormat.format(fence.createdAt))
                    fence.center?.let {
                        put("centerLat", it.latitude)
                        put("centerLng", it.longitude)
                    }
                    fence.radius?.let { put("radius", it.toDouble()) }
                    fence.points?.let { points ->
                        val pointsArray = JSONArray()
                        points.forEach { point ->
                            pointsArray.put(org.json.JSONObject().apply {
                                put("lat", point.latitude)
                                put("lng", point.longitude)
                            })
                        }
                        put("points", pointsArray)
                    }
                }
                jsonArray.put(jsonObject)
            }

            val wrapper = org.json.JSONObject().apply {
                put("version", 1)
                put("exportTime", dateFormat.format(Date()))
                put("fenceCount", fences.size)
                put("fences", jsonArray)
            }

            AppLog.d(TAG, "围栏JSON生成成功，共${fences.size}个围栏")
            return wrapper.toString(2)
        } catch (e: Exception) {
            AppLog.e(TAG, "围栏JSON生成失败", e)
            return null
        }
    }

    /**
     * 导出围栏到JSON文件
     */
    fun exportFencesToJson(fences: List<GeoFence>, context: Context): String? {
        try {
            val json = generateFencesJson(fences) ?: return null
            val fileName = "TrackMaster_Fences_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
            val file = java.io.File(context.getExternalFilesDir(null), fileName)
            file.writeText(json)
            AppLog.d(TAG, "围栏导出成功: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            AppLog.e(TAG, "围栏导出失败", e)
            return null
        }
    }

    /**
     * 从JSON导入围栏
     */
    fun importFencesFromJson(jsonContent: String, fenceDao: com.trackmaster.app.data.dao.FenceDao, preserveId: Boolean = false): Int {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            // 兼容裸数组格式（DataManager.exportFencesToJson 产出）和包装格式
            val trimmed = jsonContent.trimStart()
            val wrapper = if (trimmed.startsWith("[")) {
                org.json.JSONObject().apply {
                    put("version", 1)
                    put("fences", org.json.JSONArray(trimmed))
                }
            } else {
                org.json.JSONObject(trimmed)
            }

            val version = wrapper.optInt("version", 0)
            if (version == 0) {
                AppLog.e(TAG, "无效的围栏文件格式：缺少版本号")
                return -1
            }

            val fencesArray = wrapper.getJSONArray("fences")
            val totalCount = fencesArray.length()
            AppLog.d(TAG, "开始导入围栏，共${totalCount}个")
            var importedCount = 0

            for (i in 0 until totalCount) {
                val fenceJson = fencesArray.getJSONObject(i)
                try {
                    val type = GeoFenceType.valueOf(fenceJson.getString("type"))
                    val center = if (fenceJson.has("centerLat") && fenceJson.has("centerLng")) {
                        GeoFencePoint(fenceJson.getDouble("centerLat"), fenceJson.getDouble("centerLng"))
                    } else null
                    val radius = if (fenceJson.has("radius")) fenceJson.getDouble("radius").toFloat() else null
                    val points = if (fenceJson.has("points")) {
                        val pointsArray = fenceJson.getJSONArray("points")
                        (0 until pointsArray.length()).map { j ->
                            val pointObj = pointsArray.getJSONObject(j)
                            GeoFencePoint(pointObj.getDouble("lat"), pointObj.getDouble("lng"))
                        }
                    } else null
                    val createdAt = if (fenceJson.has("createdAt")) {
                        dateFormat.parse(fenceJson.getString("createdAt")) ?: Date()
                    } else Date()

                    val fence = GeoFence(
                        id = if (preserveId) {
                            val existing = fenceJson.optString("id", "").takeIf { it.isNotEmpty() }
                            if (existing != null && fenceDao.getFenceById(existing) == null) existing else UUID.randomUUID().toString()
                        } else {
                            UUID.randomUUID().toString()
                        },
                        name = fenceJson.getString("name"), type = type,
                        center = center, radius = radius, points = points,
                        categoryId = fenceJson.optString("categoryId", Category.UNASSIGNED_ID),
                        createdAt = createdAt,
                        color = fenceJson.optString("color", "#FF0000"),
                        alpha = fenceJson.optDouble("alpha", 0.3).toFloat()
                    )

                    if (fenceDao.insertFence(fence) > 0) {
                        importedCount++
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "导入第${i+1}个围栏失败: ${e.message}")
                }
            }
            AppLog.d(TAG, "围栏导入完成，成功${importedCount}/$totalCount 个")
            return importedCount
        } catch (e: Exception) {
            AppLog.e(TAG, "围栏导入失败", e)
            return -1
        }
    }

    private fun pointsToJson(points: List<GeoFencePoint>): String {
        val jsonArray = JSONArray()
        for (point in points) {
            jsonArray.put(org.json.JSONObject().apply {
                put("lat", point.latitude)
                put("lng", point.longitude)
            })
        }
        return jsonArray.toString()
    }

    private fun jsonToPoints(json: String): List<GeoFencePoint> {
        val points = mutableListOf<GeoFencePoint>()
        val jsonArray = JSONArray(json)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            points.add(GeoFencePoint(obj.getDouble("lat"), obj.getDouble("lng")))
        }
        return points
    }

    private fun getAllFencesInternal(): List<GeoFence> {
        val fences = mutableListOf<GeoFence>()
        val db = dbManager.readableDatabase
        val cursor = db.query(DatabaseManager.TABLE_FENCES, null, null, null, null, null, "${DatabaseManager.KEY_CREATED_AT} DESC")
        try {
            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_NAME))
                    val type = GeoFenceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_TYPE)))
                    val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_POINTS))
                    val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CREATED_AT)))
                    val categoryId = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_ID) >= 0)
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ID)) else Category.UNASSIGNED_ID
                    val color = if (cursor.getColumnIndex(DatabaseManager.KEY_COLOR) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_COLOR)))
                        cursor.getString(cursor.getColumnIndex(DatabaseManager.KEY_COLOR)) else "#FF0000"
                    val alpha = if (cursor.getColumnIndex(DatabaseManager.KEY_ALPHA) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_ALPHA)))
                        cursor.getFloat(cursor.getColumnIndex(DatabaseManager.KEY_ALPHA)) else 0.3f
                    fences.add(GeoFence(
                        id = id, name = name, type = type,
                        center = if (cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LAT) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LAT)))
                            GeoFencePoint(cursor.getDouble(cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LAT)), cursor.getDouble(cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LNG))) else null,
                        radius = if (cursor.getColumnIndex(DatabaseManager.KEY_RADIUS) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_RADIUS)))
                            cursor.getFloat(cursor.getColumnIndex(DatabaseManager.KEY_RADIUS)) else null,
                        points = if (!pointsJson.isNullOrEmpty()) jsonToPoints(pointsJson) else null,
                        categoryId = categoryId, createdAt = createdAt, color = color, alpha = alpha
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return fences
    }
}
