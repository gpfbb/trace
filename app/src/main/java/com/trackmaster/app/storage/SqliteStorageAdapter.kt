package com.trackmaster.app.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.core.DailySummary
import com.trackmaster.app.core.SegmentStatus
import com.trackmaster.app.core.StaySegment

/**
 * 基于现有 SQLite 的 StorageAdapter 实现
 *
 * 映射关系：
 * - StaySegment → stays 表（stay_id, fence_id, fence_name, enter_time, exit_time, duration）
 * - DailySummary → daily_stays 表（daily_date, daily_fence_id, daily_fence_name, daily_duration, daily_last_updated）
 *
 * 不修改现有 DB schema，通过字段映射实现零侵入对接。
 */
class SqliteStorageAdapter(
    private val dbHelper: SQLiteOpenHelper,
) : StorageAdapter {

    companion object {
        private const val TAG = "SqliteStorageAdapter"
        private const val TABLE_STAYS = "stays"
        private const val TABLE_DAILY_STAYS = "daily_stays"

        // stays 表列
        private const val COL_STAY_ID = "stay_id"
        private const val COL_FENCE_ID = "fence_id"
        private const val COL_FENCE_NAME = "fence_name"
        private const val COL_ENTER_TIME = "enter_time"
        private const val COL_EXIT_TIME = "exit_time"
        private const val COL_DURATION = "duration"

        // daily_stays 表列
        private const val COL_DAILY_DATE = "daily_date"
        private const val COL_DAILY_FENCE_ID = "daily_fence_id"
        private const val COL_DAILY_FENCE_NAME = "daily_fence_name"
        private const val COL_DAILY_DURATION = "daily_duration"
        private const val COL_DAILY_LAST_UPDATED = "daily_last_updated"
    }

    private var inTransaction = false

    // ============================================================
    // Segment 操作
    // ============================================================

    override suspend fun insertSegments(segments: List<StaySegment>) {
        val db = dbHelper.writableDatabase
        // 【Bug修复】添加批量事务保护，确保所有 segment 原子写入
        // 事务成功：全部提交；事务失败：全部回滚
        db.beginTransaction()
        try {
            for (seg in segments) {
                val values = ContentValues().apply {
                    put(COL_STAY_ID, seg.id)
                    put(COL_FENCE_ID, seg.leafGeofenceId)
                    put(COL_FENCE_NAME, seg.leafGeofenceId) // 名称由后续 join 补全
                    put(COL_ENTER_TIME, seg.startTs)
                    put(COL_EXIT_TIME, seg.endTs)
                    put(COL_DURATION, seg.endTs?.let { (it - seg.startTs) / 1000 })
                }
                db.insertWithOnConflict(TABLE_STAYS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun querySegmentsByDate(dateKey: String): List<StaySegment> {
        val db = dbHelper.readableDatabase
        val cal = java.util.Calendar.getInstance()
        val parts = dateKey.split("-")
        cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 86_400_000L

        // 查询 enter_time 在当天范围内的 Segment（可能跨天）
        val cursor = db.query(
            TABLE_STAYS, null,
            "$COL_ENTER_TIME < ? AND ($COL_EXIT_TIME IS NULL OR $COL_EXIT_TIME > ?)",
            arrayOf(dayEnd.toString(), dayStart.toString()),
            null, null,
            "$COL_ENTER_TIME ASC"
        )

        val result = mutableListOf<StaySegment>()
        try {
            while (cursor.moveToNext()) {
                val seg = cursorToSegment(cursor)
                result.add(seg)
            }
        } finally {
            cursor.close()
        }
        return result
    }

    override suspend fun queryOpenSegments(): List<StaySegment> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_STAYS, null,
            "$COL_EXIT_TIME IS NULL",
            null, null, null,
            "$COL_ENTER_TIME ASC"
        )

        val result = mutableListOf<StaySegment>()
        try {
            while (cursor.moveToNext()) {
                result.add(cursorToSegment(cursor))
            }
        } finally {
            cursor.close()
        }
        return result
    }

    override suspend fun updateSegments(segments: List<StaySegment>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (seg in segments) {
                val values = ContentValues().apply {
                    put(COL_EXIT_TIME, seg.endTs)
                    put(COL_DURATION, seg.endTs?.let { (it - seg.startTs) / 1000 })
                }
                db.update(TABLE_STAYS, values, "$COL_STAY_ID = ?", arrayOf(seg.id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ============================================================
    // DailySummary 操作
    // ============================================================

    override suspend fun saveDailySummaries(summaries: List<DailySummary>) {
        val db = dbHelper.writableDatabase
        for (s in summaries) {
            // 【Bug修复】使用 ON CONFLICT UPDATE ... = ... + excluded.xxx 增量模式
            // 避免 CONFLICT_REPLACE 覆盖 StayDurationManager 之前累加的时长数据
            db.execSQL("""
                INSERT INTO $TABLE_DAILY_STAYS ($COL_DAILY_DATE, $COL_DAILY_FENCE_ID, $COL_DAILY_FENCE_NAME, $COL_DAILY_DURATION, $COL_DAILY_LAST_UPDATED)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT($COL_DAILY_DATE, $COL_DAILY_FENCE_ID) DO UPDATE SET
                    $COL_DAILY_DURATION = $COL_DAILY_DURATION + excluded.$COL_DAILY_DURATION,
                    $COL_DAILY_FENCE_NAME = excluded.$COL_DAILY_FENCE_NAME,
                    $COL_DAILY_LAST_UPDATED = excluded.$COL_DAILY_LAST_UPDATED
            """, arrayOf(s.date, s.geofenceId, s.name, s.spatialDurationMin * 60, System.currentTimeMillis()))
        }
    }

    override suspend fun queryDailySummary(dateKey: String): List<DailySummary> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            TABLE_DAILY_STAYS, null,
            "$COL_DAILY_DATE = ?",
            arrayOf(dateKey),
            null, null,
            "$COL_DAILY_DURATION DESC"
        )

        val result = mutableListOf<DailySummary>()
        try {
            while (cursor.moveToNext()) {
                val fenceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_DAILY_FENCE_ID))
                val fenceName = cursor.getString(cursor.getColumnIndexOrThrow(COL_DAILY_FENCE_NAME))
                val durationSec = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DAILY_DURATION))
                val durationMin = (durationSec / 60).toInt()

                result.add(DailySummary(
                    date = dateKey,
                    geofenceId = fenceId,
                    name = fenceName,
                    spatialDurationMin = durationMin,
                    leafCategoryMap = emptyMap(),
                    isLeaf = true, // 从 DB 读取时默认为叶子，Phase 6 可补全
                    depth = 0,
                ))
            }
        } finally {
            cursor.close()
        }
        return result
    }

    // ============================================================
    // 事务
    // ============================================================

    override suspend fun beginTransaction() {
        dbHelper.writableDatabase.beginTransaction()
        inTransaction = true
    }

    override suspend fun commitTransaction() {
        // 【P1修复】使用try-finally确保endTransaction一定执行
        // 原bug：setTransactionSuccessful()抛异常时，endTransaction()不执行，
        // 导致inTransaction永远为true，下次beginTransaction会嵌套失败
        try {
            dbHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            dbHelper.writableDatabase.endTransaction()
            inTransaction = false
        }
    }

    override suspend fun rollbackTransaction() {
        dbHelper.writableDatabase.endTransaction()
        inTransaction = false
    }

    // ============================================================
    // 内部
    // ============================================================

    private fun cursorToSegment(cursor: android.database.Cursor): StaySegment {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_STAY_ID))
        val fenceId = cursor.getString(cursor.getColumnIndexOrThrow(COL_FENCE_ID))
        val enterTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ENTER_TIME))
        val exitTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_EXIT_TIME))) {
            null
        } else {
            cursor.getLong(cursor.getColumnIndexOrThrow(COL_EXIT_TIME))
        }

        val status = if (exitTime == null) {
            SegmentStatus.ACTIVE
        } else {
            val enterDate = Repository.formatDateKey(enterTime)
            val exitDate = Repository.formatDateKey(exitTime)
            if (enterDate != exitDate) SegmentStatus.CROSSED_DAY else SegmentStatus.COMPLETED
        }

        return StaySegment(
            id = id,
            leafGeofenceId = fenceId,
            startTs = enterTime,
            endTs = exitTime,
            status = status,
        )
    }
}
