package com.trackmaster.app

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class DailyStayRecord(
    val date: String,
    val fenceId: String,
    val fenceName: String,
    val totalDurationSeconds: Long,
    val lastUpdated: Date
)

data class FenceActiveSession(
    val fenceId: String,
    val fenceName: String,
    val enterTime: Date,
    val dateKey: String
)

class StayDurationManager(private val context: Context) {
    companion object {
        private const val TAG = "StayDurationManager"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }

    private val dbHelper = (context.applicationContext as TrackMasterApplication).dbHelper

    private val activeSessions = ConcurrentHashMap<String, FenceActiveSession>()
    private val dailyCache = ConcurrentHashMap<String, MutableMap<String, DailyStayRecord>>()

    init {
        Log.d(TAG, "停留时长管理器初始化")
        loadActiveSessionsFromDatabase()
    }

    fun getDateKey(date: Date): String {
        return DATE_FORMAT.format(date)
    }

    fun onFenceEnter(fenceId: String, fenceName: String, enterTime: Date) {
        val dateKey = getDateKey(enterTime)
        val session = FenceActiveSession(
            fenceId = fenceId,
            fenceName = fenceName,
            enterTime = enterTime,
            dateKey = dateKey
        )
        activeSessions[fenceId] = session
        Log.d(TAG, "开始计时: $fenceName, 日期=$dateKey, 时间=$enterTime")
    }

    fun onFenceExit(fenceId: String, fenceName: String, exitTime: Date) {
        val session = activeSessions.remove(fenceId) ?: run {
            Log.w(TAG, "未找到活动会话: $fenceName")
            return
        }

        val enterDateKey = session.dateKey
        val exitDateKey = getDateKey(exitTime)

        if (enterDateKey == exitDateKey) {
            val duration = (exitTime.time - session.enterTime.time) / 1000
            if (duration > 0) {
                addDailyDuration(enterDateKey, fenceId, fenceName, duration)
                Log.d(TAG, "结束计时: $fenceName, 日期=$enterDateKey, 时长=${duration}秒")
            }
        } else {
            val calendar = Calendar.getInstance()
            calendar.time = session.enterTime
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            val midnightOfEnterDate = calendar.time

            val durationDay1 = (midnightOfEnterDate.time - session.enterTime.time) / 1000
            if (durationDay1 > 0) {
                addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                Log.d(TAG, "跨天计时(第一部分): $fenceName, 日期=$enterDateKey, 时长=${durationDay1}秒")
            }

            calendar.time = exitTime
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val midnightOfExitDate = calendar.time

            val durationDay2 = (exitTime.time - midnightOfExitDate.time) / 1000
            if (durationDay2 > 0) {
                addDailyDuration(exitDateKey, fenceId, fenceName, durationDay2)
                Log.d(TAG, "跨天计时(第二部分): $fenceName, 日期=$exitDateKey, 时长=${durationDay2}秒")
            }
        }
    }

    private fun addDailyDuration(dateKey: String, fenceId: String, fenceName: String, durationSeconds: Long) {
        val existing = getDailyRecord(dateKey, fenceId)
        val newDuration = if (existing != null) {
            existing.totalDurationSeconds + durationSeconds
        } else {
            durationSeconds
        }

        val record = DailyStayRecord(
            date = dateKey,
            fenceId = fenceId,
            fenceName = fenceName,
            totalDurationSeconds = newDuration,
            lastUpdated = Date()
        )

        saveDailyRecord(record)
        Log.d(TAG, "更新日统计: $fenceName, 日期=$dateKey, 总时长=${newDuration}秒")
    }

    fun getDailyRecord(dateKey: String, fenceId: String): DailyStayRecord? {
        val dateRecords = dailyCache.getOrPut(dateKey) { mutableMapOf() }
        return dateRecords[fenceId] ?: loadDailyRecordFromDatabase(dateKey, fenceId)?.also {
            dateRecords[fenceId] = it
        }
    }

    fun getAllDailyRecords(dateKey: String): List<DailyStayRecord> {
        val fromDb = loadAllDailyRecordsFromDatabase(dateKey)
        val dateRecords = dailyCache.getOrPut(dateKey) { mutableMapOf() }
        fromDb.forEach { record ->
            dateRecords[record.fenceId] = record
        }
        return dateRecords.values.toList()
    }

    fun getMonthlyRecords(year: Int, month: Int): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = calendar.time
        
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
        val endDate = calendar.time

        val stays = dbHelper.getStayRecordsByTimeRange(startDate, endDate)
        for (stay in stays) {
            val currentDuration = result[stay.fenceId] ?: 0L
            val stayDuration = stay.durationSeconds ?: 0L
            result[stay.fenceId] = currentDuration + stayDuration
        }

        return result
    }

    fun getYearlyRecords(year: Int): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, Calendar.JANUARY)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val startDate = calendar.time
        
        calendar.set(Calendar.MONTH, Calendar.DECEMBER)
        calendar.set(Calendar.DAY_OF_MONTH, 31)
        val endDate = calendar.time

        val stays = dbHelper.getStayRecordsByTimeRange(startDate, endDate)
        for (stay in stays) {
            val currentDuration = result[stay.fenceId] ?: 0L
            val stayDuration = stay.durationSeconds ?: 0L
            result[stay.fenceId] = currentDuration + stayDuration
        }

        return result
    }

    fun getTotalDailyDuration(dateKey: String): Long {
        val records = getAllDailyRecords(dateKey)
        return records.sumOf { it.totalDurationSeconds }
    }

    fun validateDailyDuration(dateKey: String): Boolean {
        val total = getTotalDailyDuration(dateKey)
        val maxDailySeconds = 24 * 60 * 60L
        if (total > maxDailySeconds) {
            Log.e(TAG, "警告: 单日总时长 ${total}秒 超过24小时 (${maxDailySeconds}秒)!")
            return false
        }
        return true
    }

    fun getActiveSessions(): List<FenceActiveSession> {
        return activeSessions.values.toList()
    }

    fun isFenceActive(fenceId: String): Boolean {
        return activeSessions.containsKey(fenceId)
    }

    fun getCurrentDuration(fenceId: String): Long? {
        val session = activeSessions[fenceId] ?: return null
        return (System.currentTimeMillis() - session.enterTime.time) / 1000
    }

    private fun saveDailyRecord(record: DailyStayRecord) {
        val dateRecords = dailyCache.getOrPut(record.date) { mutableMapOf() }
        dateRecords[record.fenceId] = record
        dbHelper.saveDailyStayRecord(record)
    }

    private fun loadDailyRecordFromDatabase(dateKey: String, fenceId: String): DailyStayRecord? {
        return dbHelper.getDailyStayRecord(dateKey, fenceId)
    }

    private fun loadAllDailyRecordsFromDatabase(dateKey: String): List<DailyStayRecord> {
        return dbHelper.getDailyStayRecordsByDate(dateKey)
    }

    private fun loadActiveSessionsFromDatabase() {
        val openStays = dbHelper.getOpenStayRecords()
        for (stay in openStays) {
            val session = FenceActiveSession(
                fenceId = stay.fenceId,
                fenceName = stay.fenceName,
                enterTime = stay.enterTime,
                dateKey = getDateKey(stay.enterTime)
            )
            activeSessions[stay.fenceId] = session
            Log.d(TAG, "恢复活动会话: ${stay.fenceName}")
        }
    }

    fun saveAllActiveSessions() {
        Log.d(TAG, "保存所有活动会话")
    }
}
