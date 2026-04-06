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
        private val DATE_FORMAT_THREAD_LOCAL = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            }
        }

        fun formatDate(date: Date): String {
            return DATE_FORMAT_THREAD_LOCAL.get()?.format(date) ?: ""
        }
    }

    private val dbHelper = (context.applicationContext as TrackMasterApplication).dbHelper

    public val activeSessions: ConcurrentHashMap<String, FenceActiveSession> = ConcurrentHashMap()
    private val dailyCache = ConcurrentHashMap<String, MutableMap<String, DailyStayRecord>>()

    init {
        Log.d(TAG, "停留时长管理器初始化")
        loadActiveSessionsFromDatabase()
    }

    fun getDateKey(date: Date): String {
        return formatDate(date)
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
        val totalDuration = (exitTime.time - session.enterTime.time) / 1000

        // 保存停留记录到数据库
        if (totalDuration > 0) {
            val stayRecord = FenceStayRecord(
                fenceId = fenceId,
                fenceName = fenceName,
                enterTime = session.enterTime,
                exitTime = exitTime,
                durationSeconds = totalDuration
            )
            dbHelper.insertStayRecord(stayRecord)
            Log.d(TAG, "保存停留记录: $fenceName, 时长=${totalDuration}秒")
        }

        if (enterDateKey == exitDateKey) {
            val duration = (exitTime.time - session.enterTime.time) / 1000
            if (duration > 0) {
                addDailyDuration(enterDateKey, fenceId, fenceName, duration)
                Log.d(TAG, "结束计时: $fenceName, 日期=$enterDateKey, 时长=${duration}秒")
            }
        } else {
            val calendar = Calendar.getInstance()

            // 第一天：从进入时间到当天结束
            calendar.time = session.enterTime
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            val durationDay1 = (calendar.time.time - session.enterTime.time) / 1000
            if (durationDay1 > 0) {
                addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                Log.d(TAG, "跨天计时(首日): $fenceName, 日期=$enterDateKey, 时长=${durationDay1}秒")
            }

            // 中间完整天：每整天计 86400 秒
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            val exitCalendar = Calendar.getInstance()
            exitCalendar.time = exitTime
            exitCalendar.set(Calendar.HOUR_OF_DAY, 0)
            exitCalendar.set(Calendar.MINUTE, 0)
            exitCalendar.set(Calendar.SECOND, 0)
            exitCalendar.set(Calendar.MILLISECOND, 0)

            while (calendar.before(exitCalendar)) {
                val fullDayKey = getDateKey(calendar.time)
                addDailyDuration(fullDayKey, fenceId, fenceName, 86400L)
                Log.d(TAG, "跨天计时(整天): $fenceName, 日期=$fullDayKey, 时长=86400秒")
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }

            // 最后一天：从当天开始到退出时间
            val durationLastDay = (exitTime.time - exitCalendar.time.time) / 1000
            if (durationLastDay > 0) {
                addDailyDuration(exitDateKey, fenceId, fenceName, durationLastDay)
                Log.d(TAG, "跨天计时(末日): $fenceName, 日期=$exitDateKey, 时长=${durationLastDay}秒")
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

    public fun loadActiveSessionsFromDatabase() {
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
        Log.d(TAG, "保存所有活动会话, 数量=${activeSessions.size}")
        val openStays = dbHelper.getOpenStayRecords()
        val openFenceIds = openStays.map { it.fenceId }.toSet()

        for (session in activeSessions.values) {
            if (session.fenceId !in openFenceIds) {
                val stayRecord = FenceStayRecord(
                    fenceId = session.fenceId,
                    fenceName = session.fenceName,
                    enterTime = session.enterTime,
                    exitTime = null,
                    durationSeconds = null
                )
                dbHelper.insertStayRecord(stayRecord)
                Log.d(TAG, "创建开放停留记录: ${session.fenceName}")
            }
        }
    }
}
