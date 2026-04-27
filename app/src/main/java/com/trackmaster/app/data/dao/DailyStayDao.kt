package com.trackmaster.app.data.dao

import com.trackmaster.app.DailyStayRecord
import com.trackmaster.app.data.database.DatabaseManager
import com.trackmaster.app.utils.AppLog
import java.util.Date

class DailyStayDao(private val dbManager: DatabaseManager) {

    companion object {
        private const val TAG = "DailyStayDao"
    }

    fun saveDailyStayRecord(record: DailyStayRecord) {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        try {
            val existing = getDailyStayRecord(record.date, record.fenceId)
            val newDuration = if (existing != null) {
                existing.totalDurationSeconds + record.totalDurationSeconds
            } else {
                record.totalDurationSeconds
            }
            val table = DatabaseManager.TABLE_DAILY_STAYS
            db.execSQL("""
                INSERT INTO $table (
                    ${DatabaseManager.KEY_DAILY_DATE}, ${DatabaseManager.KEY_DAILY_FENCE_ID},
                    ${DatabaseManager.KEY_DAILY_FENCE_NAME}, ${DatabaseManager.KEY_DAILY_DURATION},
                    ${DatabaseManager.KEY_DAILY_LAST_UPDATED}
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(${DatabaseManager.KEY_DAILY_DATE}, ${DatabaseManager.KEY_DAILY_FENCE_ID}) DO UPDATE SET
                    ${DatabaseManager.KEY_DAILY_DURATION} = ${DatabaseManager.KEY_DAILY_DURATION} + excluded.${DatabaseManager.KEY_DAILY_DURATION},
                    ${DatabaseManager.KEY_DAILY_LAST_UPDATED} = excluded.${DatabaseManager.KEY_DAILY_LAST_UPDATED}
            """, arrayOf(record.date, record.fenceId, record.fenceName, record.totalDurationSeconds, record.lastUpdated.time))
            db.setTransactionSuccessful()
            AppLog.d(TAG, "保存日统计: ${record.fenceName}, 日期=${record.date}, 累加时长=${record.totalDurationSeconds}秒, 总计=${newDuration}秒")
        } finally {
            db.endTransaction()
        }
    }

    fun getDailyStayRecord(dateKey: String, fenceId: String): DailyStayRecord? {
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_DAILY_STAYS, null,
            "${DatabaseManager.KEY_DAILY_DATE} = ? AND ${DatabaseManager.KEY_DAILY_FENCE_ID} = ?",
            arrayOf(dateKey, fenceId), null, null, null
        )
        var record: DailyStayRecord? = null
        try {
            if (cursor.moveToFirst()) {
                record = DailyStayRecord(
                    date = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_DATE)),
                    fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_FENCE_ID)),
                    fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_FENCE_NAME)),
                    totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_DURATION)),
                    lastUpdated = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_LAST_UPDATED)))
                )
            }
        } finally {
            cursor.close()
        }
        return record
    }

    fun getDailyStayRecordsByDate(dateKey: String): List<DailyStayRecord> {
        val records = mutableListOf<DailyStayRecord>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_DAILY_STAYS, null,
            "${DatabaseManager.KEY_DAILY_DATE} = ?", arrayOf(dateKey),
            null, null, "${DatabaseManager.KEY_DAILY_DURATION} DESC"
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(DailyStayRecord(
                        date = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_DATE)),
                        fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_FENCE_ID)),
                        fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_FENCE_NAME)),
                        totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_DURATION)),
                        lastUpdated = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_LAST_UPDATED)))
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun getAllDailyStayRecords(): List<DailyStayRecord> {
        val records = mutableListOf<DailyStayRecord>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_DAILY_STAYS, null, null, null, null, null,
            "${DatabaseManager.KEY_DAILY_DATE} DESC"
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(DailyStayRecord(
                        date = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_DATE)),
                        fenceId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_FENCE_ID)),
                        fenceName = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_FENCE_NAME)),
                        totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_DURATION)),
                        lastUpdated = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_DAILY_LAST_UPDATED)))
                    ))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun upsertDailyStayRecords(records: List<DailyStayRecord>) {
        val db = dbManager.writableDatabase
        db.beginTransaction()
        try {
            val table = DatabaseManager.TABLE_DAILY_STAYS
            for (record in records) {
                db.execSQL("""
                    INSERT INTO $table (
                        ${DatabaseManager.KEY_DAILY_DATE}, ${DatabaseManager.KEY_DAILY_FENCE_ID},
                        ${DatabaseManager.KEY_DAILY_FENCE_NAME}, ${DatabaseManager.KEY_DAILY_DURATION},
                        ${DatabaseManager.KEY_DAILY_LAST_UPDATED}
                    ) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(${DatabaseManager.KEY_DAILY_DATE}, ${DatabaseManager.KEY_DAILY_FENCE_ID}) DO UPDATE SET
                        ${DatabaseManager.KEY_DAILY_FENCE_NAME} = excluded.${DatabaseManager.KEY_DAILY_FENCE_NAME},
                        ${DatabaseManager.KEY_DAILY_DURATION} = ${DatabaseManager.KEY_DAILY_DURATION} + excluded.${DatabaseManager.KEY_DAILY_DURATION},
                        ${DatabaseManager.KEY_DAILY_LAST_UPDATED} = excluded.${DatabaseManager.KEY_DAILY_LAST_UPDATED}
                """, arrayOf(record.date, record.fenceId, record.fenceName, record.totalDurationSeconds, System.currentTimeMillis()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
