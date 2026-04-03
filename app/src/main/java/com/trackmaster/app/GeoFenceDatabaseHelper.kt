package com.trackmaster.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

class GeoFenceDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "geofence.db"
        private const val DATABASE_VERSION = 1

        // 围栏表
        private const val TABLE_FENCES = "fences"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_TYPE = "type"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LNG = "center_lng"
        private const val KEY_RADIUS = "radius"
        private const val KEY_POINTS = "points"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CREATED_AT = "created_at"

        // 事件表
        private const val TABLE_EVENTS = "events"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_FENCE_ID = "fence_id"
        private const val KEY_FENCE_NAME = "fence_name"
        private const val KEY_ACTION = "action"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_ACCURACY = "accuracy"

        // 停留记录表
        private const val TABLE_STAYS = "stays"
        private const val KEY_STAY_ID = "stay_id"
        private const val KEY_ENTER_TIME = "enter_time"
        private const val KEY_EXIT_TIME = "exit_time"
        private const val KEY_DURATION = "duration"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createFencesTable = """
            CREATE TABLE $TABLE_FENCES (
                $KEY_ID TEXT PRIMARY KEY,
                $KEY_NAME TEXT NOT NULL,
                $KEY_TYPE TEXT NOT NULL,
                $KEY_CENTER_LAT REAL,
                $KEY_CENTER_LNG REAL,
                $KEY_RADIUS REAL,
                $KEY_POINTS TEXT,
                $KEY_ENABLED INTEGER DEFAULT 1,
                $KEY_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val createEventsTable = """
            CREATE TABLE $TABLE_EVENTS (
                $KEY_EVENT_ID TEXT PRIMARY KEY,
                $KEY_FENCE_ID TEXT NOT NULL,
                $KEY_FENCE_NAME TEXT NOT NULL,
                $KEY_ACTION TEXT NOT NULL,
                $KEY_TIMESTAMP INTEGER NOT NULL,
                $KEY_LATITUDE REAL NOT NULL,
                $KEY_LONGITUDE REAL NOT NULL,
                $KEY_ACCURACY REAL NOT NULL,
                FOREIGN KEY($KEY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        val createStaysTable = """
            CREATE TABLE $TABLE_STAYS (
                $KEY_STAY_ID TEXT PRIMARY KEY,
                $KEY_FENCE_ID TEXT NOT NULL,
                $KEY_FENCE_NAME TEXT NOT NULL,
                $KEY_ENTER_TIME INTEGER NOT NULL,
                $KEY_EXIT_TIME INTEGER,
                $KEY_DURATION INTEGER,
                FOREIGN KEY($KEY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        db.execSQL(createFencesTable)
        db.execSQL(createEventsTable)
        db.execSQL(createStaysTable)

        db.execSQL("CREATE INDEX idx_fences_created ON $TABLE_FENCES($KEY_CREATED_AT)")
        db.execSQL("CREATE INDEX idx_events_fence ON $TABLE_EVENTS($KEY_FENCE_ID)")
        db.execSQL("CREATE INDEX idx_events_timestamp ON $TABLE_EVENTS($KEY_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_stays_fence ON $TABLE_STAYS($KEY_FENCE_ID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_STAYS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FENCES")
        onCreate(db)
    }

    fun insertFence(fence: GeoFence): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_ID, fence.id)
        values.put(KEY_NAME, fence.name)
        values.put(KEY_TYPE, fence.type.name)
        values.put(KEY_CENTER_LAT, fence.center?.latitude)
        values.put(KEY_CENTER_LNG, fence.center?.longitude)
        values.put(KEY_RADIUS, fence.radius)
        values.put(KEY_POINTS, fence.points?.let { pointsToJson(it) })
        values.put(KEY_ENABLED, 1)
        values.put(KEY_CREATED_AT, fence.createdAt.time)

        val result = db.insert(TABLE_FENCES, null, values)
        db.close()
        return result
    }

    fun getAllFences(): List<GeoFence> {
        val fences = mutableListOf<GeoFence>()
        val db = readableDatabase
        val cursor = db.query(TABLE_FENCES, null, null, null, null, null, "$KEY_CREATED_AT DESC")

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_NAME))
                val type = GeoFenceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(KEY_TYPE)))
                val centerLat = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_CENTER_LAT))
                val centerLng = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_CENTER_LNG))
                val radius = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_RADIUS))
                val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(KEY_POINTS))
                val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CREATED_AT)))

                val center = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_CENTER_LAT))) {
                    GeoFencePoint(centerLat, centerLng)
                } else null

                val points = if (pointsJson != null) jsonToPoints(pointsJson) else null

                fences.add(
                    GeoFence(
                        id = id,
                        name = name,
                        type = type,
                        center = center,
                        radius = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_RADIUS))) radius else null,
                        points = points,
                        createdAt = createdAt
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return fences
    }

    fun deleteFence(fenceId: String): Int {
        val db = writableDatabase
        val result = db.delete(TABLE_FENCES, "$KEY_ID = ?", arrayOf(fenceId))
        db.close()
        return result
    }

    fun deleteAllFences(): Int {
        val db = writableDatabase
        val result = db.delete(TABLE_FENCES, null, null)
        db.close()
        return result
    }

    fun insertEvent(event: GeoFenceEvent): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_EVENT_ID, event.id)
        values.put(KEY_FENCE_ID, event.fenceId)
        values.put(KEY_FENCE_NAME, event.fenceName)
        values.put(KEY_ACTION, event.action.name)
        values.put(KEY_TIMESTAMP, event.time.time)
        values.put(KEY_LATITUDE, event.latitude)
        values.put(KEY_LONGITUDE, event.longitude)
        values.put(KEY_ACCURACY, event.accuracy)

        val result = db.insert(TABLE_EVENTS, null, values)
        db.close()
        return result
    }

    fun insertEvents(events: List<GeoFenceEvent>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (event in events) {
                val values = ContentValues()
                values.put(KEY_EVENT_ID, event.id)
                values.put(KEY_FENCE_ID, event.fenceId)
                values.put(KEY_FENCE_NAME, event.fenceName)
                values.put(KEY_ACTION, event.action.name)
                values.put(KEY_TIMESTAMP, event.time.time)
                values.put(KEY_LATITUDE, event.latitude)
                values.put(KEY_LONGITUDE, event.longitude)
                values.put(KEY_ACCURACY, event.accuracy)
                db.insert(TABLE_EVENTS, null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.close()
        return count
    }

    fun getEventsByFence(fenceId: String, limit: Int = 100): List<GeoFenceEvent> {
        return getEvents("$KEY_FENCE_ID = ?", arrayOf(fenceId), limit)
    }

    fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent> {
        return getEvents(
            "$KEY_TIMESTAMP >= ? AND $KEY_TIMESTAMP <= ?",
            arrayOf(startTime.time.toString(), endTime.time.toString()),
            1000
        )
    }

    private fun getEvents(selection: String, selectionArgs: Array<String>, limit: Int): List<GeoFenceEvent> {
        val events = mutableListOf<GeoFenceEvent>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EVENTS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$KEY_TIMESTAMP DESC",
            limit.toString()
        )

        if (cursor.moveToFirst()) {
            do {
                events.add(cursorToEvent(cursor))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return events
    }

    private fun cursorToEvent(cursor: android.database.Cursor): GeoFenceEvent {
        return GeoFenceEvent(
            id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_EVENT_ID)),
            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_ID)),
            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_NAME)),
            action = GeoFenceAction.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(KEY_ACTION))),
            time = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP))),
            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_LATITUDE)),
            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_LONGITUDE)),
            accuracy = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_ACCURACY)),
            durationSeconds = null
        )
    }

    fun insertStayRecord(record: FenceStayRecord): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_STAY_ID, UUID.randomUUID().toString())
        values.put(KEY_FENCE_ID, record.fenceId)
        values.put(KEY_FENCE_NAME, record.fenceName)
        values.put(KEY_ENTER_TIME, record.enterTime.time)
        values.put(KEY_EXIT_TIME, record.exitTime?.time)
        values.put(KEY_DURATION, record.durationSeconds)

        val result = db.insert(TABLE_STAYS, null, values)
        db.close()
        return result
    }

    fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            null,
            "$KEY_FENCE_ID = ?",
            arrayOf(fenceId),
            null,
            null,
            "$KEY_ENTER_TIME DESC"
        )

        if (cursor.moveToFirst()) {
            do {
                records.add(cursorToStayRecord(cursor))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return records
    }

    fun getAllStayRecords(): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = readableDatabase
        val cursor = db.query(TABLE_STAYS, null, null, null, null, null, "$KEY_ENTER_TIME DESC")

        if (cursor.moveToFirst()) {
            do {
                records.add(cursorToStayRecord(cursor))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return records
    }

    private fun cursorToStayRecord(cursor: android.database.Cursor): FenceStayRecord {
        return FenceStayRecord(
            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_ID)),
            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_NAME)),
            enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ENTER_TIME))),
            exitTime = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME))) {
                Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME)))
            } else null,
            durationSeconds = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_DURATION))) {
                cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DURATION))
            } else null
        )
    }

    fun getFenceStats(fenceId: String): Long {
        var totalDuration = 0L
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            arrayOf("SUM($KEY_DURATION) as total"),
            "$KEY_FENCE_ID = ? AND $KEY_DURATION IS NOT NULL",
            arrayOf(fenceId),
            null,
            null,
            null
        )

        if (cursor.moveToFirst()) {
            totalDuration = cursor.getLong(0)
        }
        cursor.close()
        db.close()
        return totalDuration
    }

    fun getDailyStats(date: String): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            arrayOf(KEY_FENCE_NAME, "SUM($KEY_DURATION) as total"),
            "DATE($KEY_ENTER_TIME/1000, 'unixepoch') = ?",
            arrayOf(date),
            KEY_FENCE_NAME,
            null,
            null
        )

        if (cursor.moveToFirst()) {
            do {
                val name = cursor.getString(0)
                val total = cursor.getLong(1)
                stats[name] = total
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return stats
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
            points.add(
                GeoFencePoint(
                    latitude = obj.getDouble("lat"),
                    longitude = obj.getDouble("lng")
                )
            )
        }
        return points
    }

    fun exportToCSV(context: Context): String? {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val csvContent = StringBuilder()

            csvContent.append("=== 围栏数据 ===\n")
            csvContent.append("ID,名称,类型,创建时间\n")
            getAllFences().forEach { fence ->
                csvContent.append("${fence.id},${fence.name},${fence.type},${dateFormat.format(fence.createdAt)}\n")
            }

            csvContent.append("\n=== 事件记录 ===\n")
            csvContent.append("事件ID,围栏ID,围栏名称,类型,时间,纬度,经度,精度\n")
            getEventsByTimeRange(Date(0), Date()).forEach { event ->
                csvContent.append("${event.id},${event.fenceId},${event.fenceName},${event.action},${dateFormat.format(event.time)},${event.latitude},${event.longitude},${event.accuracy}\n")
            }

            csvContent.append("\n=== 停留记录 ===\n")
            csvContent.append("围栏ID,围栏名称,进入时间,离开时间,停留时长(秒)\n")
            getAllStayRecords().forEach { record ->
                csvContent.append("${record.fenceId},${record.fenceName},${dateFormat.format(record.enterTime)},${record.exitTime?.let { dateFormat.format(it) } ?: ""},${record.durationSeconds ?: ""}\n")
            }

            val fileName = "GeoFenceExport_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val file = java.io.File(context.getExternalFilesDir(null), fileName)
            file.writeText(csvContent.toString())

            Log.d("GeoFenceDB", "CSV导出成功: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e("GeoFenceDB", "CSV导出失败", e)
            return null
        }
    }
}
