package com.trackmaster.app.data.dao

import android.content.ContentValues
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.data.database.DatabaseManager
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.model.Category
import com.trackmaster.app.utils.AppLog
import org.json.JSONArray
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FenceDao(private val dbManager: DatabaseManager) {

    companion object {
        private const val TAG = "FenceDao"
    }

    // 内存缓存
    private val fenceCache = ConcurrentHashMap<String, GeoFence>()
    private val allFencesCache = CopyOnWriteArrayList<GeoFence>()
    private val allFencesCacheValid = java.util.concurrent.atomic.AtomicBoolean(false)

    fun invalidateCache() {
        fenceCache.clear()
        allFencesCache.clear()
        allFencesCacheValid.set(false)
        AppLog.d(TAG, "围栏缓存已失效")
    }

    private fun pointsToJson(points: List<GeoFencePoint>): String {
        val jsonArray = JSONArray()
        for (point in points) {
            val obj = org.json.JSONObject()
            obj.put("lat", point.latitude)
            obj.put("lng", point.longitude)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun jsonToPoints(json: String): List<GeoFencePoint> {
        val points = mutableListOf<GeoFencePoint>()
        val jsonArray = JSONArray(json)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            points.add(GeoFencePoint(
                latitude = obj.getDouble("lat"),
                longitude = obj.getDouble("lng")
            ))
        }
        return points
    }

    private fun cursorToFence(cursor: android.database.Cursor): GeoFence {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ID))
        val name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_NAME))
        val type = GeoFenceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_TYPE)))
        val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_POINTS))
        val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CREATED_AT)))
        val categoryId = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_ID) >= 0) {
            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ID))
        } else Category.UNASSIGNED_ID
        val color = if (cursor.getColumnIndex(DatabaseManager.KEY_COLOR) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_COLOR))) {
            cursor.getString(cursor.getColumnIndex(DatabaseManager.KEY_COLOR))
        } else "#FF0000"
        val alpha = if (cursor.getColumnIndex(DatabaseManager.KEY_ALPHA) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_ALPHA))) {
            cursor.getFloat(cursor.getColumnIndex(DatabaseManager.KEY_ALPHA))
        } else 0.3f
        val center = if (cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LAT) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LAT))) {
            GeoFencePoint(
                cursor.getDouble(cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LAT)),
                cursor.getDouble(cursor.getColumnIndex(DatabaseManager.KEY_CENTER_LNG))
            )
        } else null
        val radius = if (cursor.getColumnIndex(DatabaseManager.KEY_RADIUS) >= 0 && !cursor.isNull(cursor.getColumnIndex(DatabaseManager.KEY_RADIUS))) {
            cursor.getFloat(cursor.getColumnIndex(DatabaseManager.KEY_RADIUS))
        } else null
        val points = if (!pointsJson.isNullOrEmpty()) jsonToPoints(pointsJson) else null
        return GeoFence(
            id = id, name = name, type = type,
            center = center, radius = radius, points = points,
            categoryId = categoryId, createdAt = createdAt,
            color = color, alpha = alpha
        )
    }

    fun insertFence(fence: GeoFence): Long {
        invalidateCache()
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_ID, fence.id)
        values.put(DatabaseManager.KEY_NAME, fence.name)
        values.put(DatabaseManager.KEY_TYPE, fence.type.name)
        values.put(DatabaseManager.KEY_CENTER_LAT, fence.center?.latitude)
        values.put(DatabaseManager.KEY_CENTER_LNG, fence.center?.longitude)
        values.put(DatabaseManager.KEY_RADIUS, fence.radius)
        values.put(DatabaseManager.KEY_POINTS, fence.points?.let { pointsToJson(it) })
        values.put(DatabaseManager.KEY_ENABLED, 1)
        values.put(DatabaseManager.KEY_CREATED_AT, fence.createdAt.time)
        values.put(DatabaseManager.KEY_CATEGORY_ID, fence.categoryId)
        values.put(DatabaseManager.KEY_COLOR, fence.color)
        values.put(DatabaseManager.KEY_ALPHA, fence.alpha)
        return db.insert(DatabaseManager.TABLE_FENCES, null, values)
    }

    fun getAllFences(): List<GeoFence> {
        if (allFencesCacheValid.get()) {
            return ArrayList(allFencesCache)
        }
        val fences = mutableListOf<GeoFence>()
        val db = dbManager.readableDatabase
        val cursor = db.query(DatabaseManager.TABLE_FENCES, null, null, null, null, null, "${DatabaseManager.KEY_CREATED_AT} DESC")
        try {
            if (cursor.moveToFirst()) {
                do {
                    try {
                        val fence = cursorToFence(cursor)
                        fences.add(fence)
                        fenceCache[fence.id] = fence
                    } catch (e: Exception) {
                        AppLog.e(TAG, "读取单个围栏出错: ${e.message}")
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        allFencesCache.clear()
        allFencesCache.addAll(fences)
        allFencesCacheValid.set(true)
        return fences
    }

    fun getFenceById(fenceId: String): GeoFence? {
        fenceCache[fenceId]?.let { return it }
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_FENCES, null,
            "${DatabaseManager.KEY_ID} = ?", arrayOf(fenceId),
            null, null, null
        )
        try {
            if (cursor.moveToFirst()) {
                val fence = cursorToFence(cursor)
                fenceCache[fenceId] = fence
                return fence
            }
        } finally {
            cursor.close()
        }
        return null
    }

    fun updateFence(fence: GeoFence): Int {
        invalidateCache()
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_NAME, fence.name)
        values.put(DatabaseManager.KEY_TYPE, fence.type.name)
        values.put(DatabaseManager.KEY_CENTER_LAT, fence.center?.latitude)
        values.put(DatabaseManager.KEY_CENTER_LNG, fence.center?.longitude)
        values.put(DatabaseManager.KEY_RADIUS, fence.radius)
        values.put(DatabaseManager.KEY_POINTS, fence.points?.let { pointsToJson(it) })
        values.put(DatabaseManager.KEY_CATEGORY_ID, fence.categoryId)
        values.put(DatabaseManager.KEY_COLOR, fence.color)
        values.put(DatabaseManager.KEY_ALPHA, fence.alpha)
        return db.update(DatabaseManager.TABLE_FENCES, values, "${DatabaseManager.KEY_ID} = ?", arrayOf(fence.id))
    }

    fun deleteFence(fenceId: String): Int {
        invalidateCache()
        val db = dbManager.writableDatabase
        return db.delete(DatabaseManager.TABLE_FENCES, "${DatabaseManager.KEY_ID} = ?", arrayOf(fenceId))
    }

    fun deleteAllFences(): Int {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        try {
            db.delete(DatabaseManager.TABLE_DAILY_STAYS, null, null)
            db.delete(DatabaseManager.TABLE_STAYS, null, null)
            db.delete(DatabaseManager.TABLE_EVENTS, null, null)
            val result = db.delete(DatabaseManager.TABLE_FENCES, null, null)
            db.setTransactionSuccessful()
            db.endTransaction()
            invalidateCache()
            return result
        } catch (e: Exception) {
            db.endTransaction()
            AppLog.e(TAG, "删除所有围栏失败", e)
            invalidateCache()
            return 0
        }
    }

    fun getFencesByCategory(categoryId: String): List<GeoFence> {
        val fences = mutableListOf<GeoFence>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_FENCES, null,
            "${DatabaseManager.KEY_CATEGORY_ID} = ?", arrayOf(categoryId),
            null, null, "${DatabaseManager.KEY_CREATED_AT} DESC"
        )
        try {
            if (cursor.moveToFirst()) {
                do { fences.add(cursorToFence(cursor)) } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return fences
    }

    fun moveFencesToCategory(fromCategoryId: String, toCategoryId: String): Int {
        invalidateCache()
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_CATEGORY_ID, toCategoryId)
        return db.update(DatabaseManager.TABLE_FENCES, values, "${DatabaseManager.KEY_CATEGORY_ID} = ?", arrayOf(fromCategoryId))
    }

    fun updateFenceCategory(fenceId: String, categoryId: String): Int {
        invalidateCache()
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_CATEGORY_ID, categoryId)
        return db.update(DatabaseManager.TABLE_FENCES, values, "${DatabaseManager.KEY_ID} = ?", arrayOf(fenceId))
    }
}
