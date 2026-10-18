package com.trackmaster.app.data.dao

import android.content.ContentValues
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.database.DatabaseManager
import java.util.Date

class EventDao(private val dbManager: DatabaseManager) {

    fun insertEvent(event: GeoFenceEvent): Long {
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_EVENT_ID, event.id)
        values.put(DatabaseManager.KEY_FENCE_ID, event.fenceId)
        values.put(DatabaseManager.KEY_FENCE_NAME, event.fenceName)
        values.put(DatabaseManager.KEY_ACTION, event.action.name)
        values.put(DatabaseManager.KEY_TIMESTAMP, event.time.time)
        values.put(DatabaseManager.KEY_LATITUDE, event.latitude)
        values.put(DatabaseManager.KEY_LONGITUDE, event.longitude)
        values.put(DatabaseManager.KEY_ACCURACY, event.accuracy)
        return db.insert(DatabaseManager.TABLE_EVENTS, null, values)
    }

    fun insertEvents(events: List<GeoFenceEvent>): Int {
        val db = dbManager.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (event in events) {
                val values = ContentValues()
                values.put(DatabaseManager.KEY_EVENT_ID, event.id)
                values.put(DatabaseManager.KEY_FENCE_ID, event.fenceId)
                values.put(DatabaseManager.KEY_FENCE_NAME, event.fenceName)
                values.put(DatabaseManager.KEY_ACTION, event.action.name)
                values.put(DatabaseManager.KEY_TIMESTAMP, event.time.time)
                values.put(DatabaseManager.KEY_LATITUDE, event.latitude)
                values.put(DatabaseManager.KEY_LONGITUDE, event.longitude)
                values.put(DatabaseManager.KEY_ACCURACY, event.accuracy)
                db.insert(DatabaseManager.TABLE_EVENTS, null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun insertEventsWithId(events: List<GeoFenceEvent>) {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        try {
            for (event in events) {
                db.execSQL("""
                    INSERT INTO ${DatabaseManager.TABLE_EVENTS} (
                        ${DatabaseManager.KEY_EVENT_ID}, ${DatabaseManager.KEY_FENCE_ID}, ${DatabaseManager.KEY_FENCE_NAME},
                        ${DatabaseManager.KEY_ACTION}, ${DatabaseManager.KEY_TIMESTAMP}, ${DatabaseManager.KEY_LATITUDE},
                        ${DatabaseManager.KEY_LONGITUDE}, ${DatabaseManager.KEY_ACCURACY}
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(${DatabaseManager.KEY_EVENT_ID}) DO UPDATE SET
                        ${DatabaseManager.KEY_FENCE_NAME} = excluded.${DatabaseManager.KEY_FENCE_NAME},
                        ${DatabaseManager.KEY_ACTION} = excluded.${DatabaseManager.KEY_ACTION},
                        ${DatabaseManager.KEY_TIMESTAMP} = excluded.${DatabaseManager.KEY_TIMESTAMP},
                        ${DatabaseManager.KEY_LATITUDE} = excluded.${DatabaseManager.KEY_LATITUDE},
                        ${DatabaseManager.KEY_LONGITUDE} = excluded.${DatabaseManager.KEY_LONGITUDE},
                        ${DatabaseManager.KEY_ACCURACY} = excluded.${DatabaseManager.KEY_ACCURACY}
                """, arrayOf(
                    event.id, event.fenceId, event.fenceName, event.action.name,
                    event.time.time, event.latitude, event.longitude, event.accuracy
                ))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getEventsByFence(fenceId: String, limit: Int = 100): List<GeoFenceEvent> {
        return getEvents("${DatabaseManager.KEY_FENCE_ID} = ?", arrayOf(fenceId), limit)
    }

    fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent> {
        return getEvents(
            "${DatabaseManager.KEY_TIMESTAMP} >= ? AND ${DatabaseManager.KEY_TIMESTAMP} <= ?",
            arrayOf(startTime.time.toString(), endTime.time.toString()), 1000
        )
    }

    /**
     * 统计时间范围内各围栏的进入次数。
     *
     * 用 SQL COUNT 直接聚合，避免为了计数把整段时间的 event 全查进内存
     * （原先 getEventsByTimeRange 有 1000 条硬上限，跨月/年的进入次数会被
     * 静默截断）。
     */
    fun countEnterEventsByFence(startTime: Date, endTime: Date): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_EVENTS,
            arrayOf(
                DatabaseManager.KEY_FENCE_ID,
                "COUNT(*) AS cnt"
            ),
            "${DatabaseManager.KEY_TIMESTAMP} >= ? AND ${DatabaseManager.KEY_TIMESTAMP} <= ? " +
                "AND ${DatabaseManager.KEY_ACTION} = ?",
            arrayOf(startTime.time.toString(), endTime.time.toString(), GeoFenceAction.ENTER.name),
            DatabaseManager.KEY_FENCE_ID,
            null,
            "cnt DESC"
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    val fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID))
                    val count = cursor.getInt(cursor.getColumnIndexOrThrow("cnt"))
                    result[fenceId] = count
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return result
    }

    /**
     * 统计时间范围内指定动作的事件数。
     *
     * 首页的"今日/本周进出次数"原先靠把全量 event 读进内存再 filter 计数，
     * 现在改为 SQL 直接 COUNT。
     */
    fun countEventsByAction(action: GeoFenceAction, startTime: Date, endTime: Date): Int {
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_EVENTS,
            arrayOf("COUNT(*) AS cnt"),
            "${DatabaseManager.KEY_TIMESTAMP} >= ? AND ${DatabaseManager.KEY_TIMESTAMP} <= ? " +
                "AND ${DatabaseManager.KEY_ACTION} = ?",
            arrayOf(startTime.time.toString(), endTime.time.toString(), action.name),
            null, null, null
        )
        return try {
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow("cnt")) else 0
        } finally {
            cursor.close()
        }
    }

    fun getAllEvents(limit: Int = 10000): List<GeoFenceEvent> {
        return getEvents(null, emptyArray(), limit)
    }

    fun deleteEventsBefore(beforeDate: Date?): Int {
        val db = dbManager.writableDatabase
        return if (beforeDate != null) {
            db.delete(DatabaseManager.TABLE_EVENTS, "${DatabaseManager.KEY_TIMESTAMP} < ?", arrayOf(beforeDate.time.toString()))
        } else {
            db.delete(DatabaseManager.TABLE_EVENTS, null, null)
        }
    }

    private fun getEvents(selection: String?, selectionArgs: Array<String>?, limit: Int): List<GeoFenceEvent> {
        val events = mutableListOf<GeoFenceEvent>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_EVENTS, null, selection, selectionArgs,
            null, null, "${DatabaseManager.KEY_TIMESTAMP} DESC", limit.toString()
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    events.add(cursorToEvent(cursor))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return events
    }

    private fun cursorToEvent(cursor: android.database.Cursor): GeoFenceEvent {
        return GeoFenceEvent(
            id = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EVENT_ID)),
            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID)),
            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_NAME)),
            action = GeoFenceAction.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ACTION))),
            time = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_TIMESTAMP))),
            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_LATITUDE)),
            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_LONGITUDE)),
            accuracy = cursor.getFloat(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ACCURACY)),
            durationSeconds = null
        )
    }
}
