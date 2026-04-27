package com.trackmaster.app.data.dao

import android.content.ContentValues
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.data.DailyFenceStats
import com.trackmaster.app.data.database.DatabaseManager
import com.trackmaster.app.utils.AppLog
import java.util.Date
import java.util.UUID

class StayDao(private val dbManager: DatabaseManager) {

    companion object {
        private const val TAG = "StayDao"
    }

    fun insertStayRecord(record: FenceStayRecord): Long {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues()
            values.put(DatabaseManager.KEY_STAY_ID, UUID.randomUUID().toString())
            values.put(DatabaseManager.KEY_FENCE_ID, record.fenceId)
            values.put(DatabaseManager.KEY_FENCE_NAME, record.fenceName)
            values.put(DatabaseManager.KEY_ENTER_TIME, record.enterTime.time)
            values.put(DatabaseManager.KEY_EXIT_TIME, record.exitTime?.time)
            values.put(DatabaseManager.KEY_DURATION, record.durationSeconds)
            val result = db.insert(DatabaseManager.TABLE_STAYS, null, values)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun insertStayRecordsWithId(records: List<FenceStayRecord>) {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        try {
            for (record in records) {
                db.execSQL("""
                    INSERT INTO ${DatabaseManager.TABLE_STAYS} (
                        ${DatabaseManager.KEY_STAY_ID}, ${DatabaseManager.KEY_FENCE_ID}, ${DatabaseManager.KEY_FENCE_NAME},
                        ${DatabaseManager.KEY_ENTER_TIME}, ${DatabaseManager.KEY_EXIT_TIME}, ${DatabaseManager.KEY_DURATION}
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(${DatabaseManager.KEY_STAY_ID}) DO UPDATE SET
                        ${DatabaseManager.KEY_FENCE_NAME} = excluded.${DatabaseManager.KEY_FENCE_NAME},
                        ${DatabaseManager.KEY_ENTER_TIME} = excluded.${DatabaseManager.KEY_ENTER_TIME},
                        ${DatabaseManager.KEY_EXIT_TIME} = excluded.${DatabaseManager.KEY_EXIT_TIME},
                        ${DatabaseManager.KEY_DURATION} = excluded.${DatabaseManager.KEY_DURATION}
                """, arrayOf(
                    record.id ?: UUID.randomUUID().toString(),
                    record.fenceId, record.fenceName,
                    record.enterTime.time, record.exitTime?.time, record.durationSeconds
                ))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateOpenStayAccumulatedDuration(fenceId: String, accumulatedDuration: Long): Int {
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_DURATION, accumulatedDuration)
        val result = db.update(
            DatabaseManager.TABLE_STAYS, values,
            "${DatabaseManager.KEY_FENCE_ID} = ? AND ${DatabaseManager.KEY_EXIT_TIME} IS NULL",
            arrayOf(fenceId)
        )
        AppLog.d(TAG, "更新开放停留累积时长: fenceId=$fenceId, duration=$accumulatedDuration, updatedRows=$result")
        return result
    }

    fun closeOpenStayRecord(fenceId: String, exitTime: Date, durationSeconds: Long): Int {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues()
            values.put(DatabaseManager.KEY_EXIT_TIME, exitTime.time)
            values.put(DatabaseManager.KEY_DURATION, durationSeconds)
            val result = db.update(
                DatabaseManager.TABLE_STAYS, values,
                "${DatabaseManager.KEY_FENCE_ID} = ? AND ${DatabaseManager.KEY_EXIT_TIME} IS NULL",
                arrayOf(fenceId)
            )
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_STAYS, null,
            "${DatabaseManager.KEY_FENCE_ID} = ?", arrayOf(fenceId),
            null, null, "${DatabaseManager.KEY_ENTER_TIME} DESC"
        )
        try {
            if (cursor.moveToFirst()) {
                do { records.add(cursorToStayRecord(cursor)) } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun getAllStayRecords(): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = dbManager.readableDatabase
        val cursor = db.query(DatabaseManager.TABLE_STAYS, null, null, null, null, null, "${DatabaseManager.KEY_ENTER_TIME} DESC")
        try {
            if (cursor.moveToFirst()) {
                do { records.add(cursorToStayRecord(cursor)) } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun getStayRecordsByTimeRange(startTime: Date, endTime: Date): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_STAYS, null,
            "${DatabaseManager.KEY_ENTER_TIME} >= ? AND ${DatabaseManager.KEY_ENTER_TIME} <= ?",
            arrayOf(startTime.time.toString(), endTime.time.toString()),
            null, null, "${DatabaseManager.KEY_ENTER_TIME} DESC"
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(FenceStayRecord(
                        fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID)),
                        fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_NAME)),
                        enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ENTER_TIME))),
                        exitTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME))) null
                            else Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME))),
                        durationSeconds = if (cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION))) null
                            else cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION))
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun getOpenStayRecords(): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_STAYS, null,
            "${DatabaseManager.KEY_EXIT_TIME} IS NULL",
            null, null, null, null
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(FenceStayRecord(
                        fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID)),
                        fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_NAME)),
                        enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ENTER_TIME))),
                        exitTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME))) null
                            else Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME))),
                        durationSeconds = if (cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION))) null
                            else cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION))
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun deleteStaysBefore(beforeDate: Date?): Int {
        val db = dbManager.writableDatabase
        return if (beforeDate != null) {
            db.delete(DatabaseManager.TABLE_STAYS, "${DatabaseManager.KEY_ENTER_TIME} < ?", arrayOf(beforeDate.time.toString()))
        } else {
            db.delete(DatabaseManager.TABLE_STAYS, null, null)
        }
    }

    fun getFenceStats(fenceId: String): Long {
        var totalDuration = 0L
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_STAYS,
            arrayOf("SUM(${DatabaseManager.KEY_DURATION}) as total"),
            "${DatabaseManager.KEY_FENCE_ID} = ? AND ${DatabaseManager.KEY_DURATION} IS NOT NULL",
            arrayOf(fenceId), null, null, null
        )
        try {
            if (cursor.moveToFirst()) { totalDuration = cursor.getLong(0) }
        } finally {
            cursor.close()
        }
        return totalDuration
    }

    fun getDailyStats(date: String): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_STAYS,
            arrayOf(DatabaseManager.KEY_FENCE_ID, "SUM(${DatabaseManager.KEY_DURATION}) as total"),
            "DATE(${DatabaseManager.KEY_ENTER_TIME}/1000, 'unixepoch') = ?",
            arrayOf(date), DatabaseManager.KEY_FENCE_ID, null, null
        )
        try {
            if (cursor.moveToFirst()) {
                do { stats[cursor.getString(0)] = cursor.getLong(1) } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return stats
    }

    fun getDailyStatsByFenceAndDate(fenceId: String, date: String): DailyFenceStats? {
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_STAYS,
            arrayOf(
                DatabaseManager.KEY_FENCE_ID, DatabaseManager.KEY_FENCE_NAME,
                "SUM(${DatabaseManager.KEY_DURATION}) as total_duration",
                "COUNT(*) as enter_count", "COUNT(${DatabaseManager.KEY_EXIT_TIME}) as exit_count"
            ),
            "${DatabaseManager.KEY_FENCE_ID} = ? AND DATE(${DatabaseManager.KEY_ENTER_TIME}/1000, 'unixepoch') = ?",
            arrayOf(fenceId, date), null, null, null
        )
        var stats: DailyFenceStats? = null
        try {
            if (cursor.moveToFirst()) {
                stats = DailyFenceStats(
                    date = date,
                    fenceId = cursor.getString(0),
                    fenceName = cursor.getString(1),
                    totalStayDuration = cursor.getLong(2),
                    enterCount = cursor.getInt(3),
                    exitCount = cursor.getInt(4)
                )
            }
        } finally {
            cursor.close()
        }
        return stats
    }

    private fun cursorToStayRecord(cursor: android.database.Cursor): FenceStayRecord {
        return FenceStayRecord(
            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_ID)),
            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_FENCE_NAME)),
            enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_ENTER_TIME))),
            exitTime = if (!cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME)))
                Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_EXIT_TIME))) else null,
            durationSeconds = if (!cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION)))
                cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DURATION)) else null
        )
    }
}
