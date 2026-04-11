package com.trackmaster.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.trackmaster.app.data.FenceStayRecord
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
    val dateKey: String,
    val accumulatedDuration: Long = 0L,
    val lastSaveTime: Date? = null
)

class StayDurationManager(private val context: Context) {
    companion object {
        private const val TAG = "StayDurationManager"
        private const val PERIODIC_SAVE_INTERVAL_MS = 5 * 60 * 1000L // 5分钟
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

        // 启动定时保存：每5分钟自动保存一次活跃会话到数据库
        // 这样即使App被后台清理或强制关闭，已累积的时长也会被保存
        val periodicSaveHandler = Handler(Looper.getMainLooper())
        val saveRunnable = object : Runnable {
            override fun run() {
                if (activeSessions.isNotEmpty()) {
                    Log.d(TAG, "定时保存活跃会话: ${activeSessions.size}个")
                    saveAllActiveSessions()
                }
                periodicSaveHandler.postDelayed(this, PERIODIC_SAVE_INTERVAL_MS)
            }
        }
        periodicSaveHandler.postDelayed(saveRunnable, PERIODIC_SAVE_INTERVAL_MS)
    }

    fun getDateKey(date: Date): String {
        return formatDate(date)
    }

    fun onFenceEnter(fenceId: String, fenceName: String, enterTime: Date) {
        val dateKey = getDateKey(enterTime)

        // 【关键防御】如果该围栏已在 activeSessions 中，先关闭它
        // 防止漏掉 onFenceExit 导致的重复计时问题
        if (activeSessions.containsKey(fenceId)) {
            Log.w(TAG, "围栏 $fenceName 仍在 activeSessions 中，先关闭旧会话再启动新会话")
            onFenceExit(fenceId, fenceName, enterTime)
        }

        // 【修复】检查是否已有该围栏的未关闭记录，若有则先关闭
        // 防止App被杀死后重启时重复进入导致多条开放记录
        val existingOpenStays = dbHelper.getOpenStayRecords().filter { it.fenceId == fenceId }
        if (existingOpenStays.isNotEmpty()) {
            for (oldStay in existingOpenStays) {
                // 将旧记录标记为异常关闭
                // duration = 从旧进入时间到新进入时间（中间App可能在其他地方）
                val totalDuration = (enterTime.time - oldStay.enterTime.time) / 1000
                val closedRecord = FenceStayRecord(
                    fenceId = oldStay.fenceId,
                    fenceName = oldStay.fenceName,
                    enterTime = oldStay.enterTime,
                    exitTime = enterTime,  // 新进入时间作为异常退出时间
                    durationSeconds = totalDuration
                )
                // 插入已关闭记录
                dbHelper.insertStayRecord(closedRecord)
                Log.w(TAG, "关闭异常开放记录: $fenceName, 进入=${oldStay.enterTime}, 异常退出=$enterTime, 累积时长=${totalDuration}秒")
            }
        }

        val session = FenceActiveSession(
            fenceId = fenceId,
            fenceName = fenceName,
            enterTime = enterTime,
            dateKey = dateKey
        )
        activeSessions[fenceId] = session

        // 【修复】立即创建开放记录，不依赖5分钟定时保存
        // 这样App被杀死后重启能正确恢复计时
        val stayRecord = FenceStayRecord(
            fenceId = fenceId,
            fenceName = fenceName,
            enterTime = enterTime,
            exitTime = null,  // 开放记录
            durationSeconds = 0L
        )
        dbHelper.insertStayRecord(stayRecord)
        Log.d(TAG, "创建开放停留记录: $fenceName, 进入时间=$enterTime")

        Log.d(TAG, "开始计时: $fenceName, 日期=$dateKey, 时间=$enterTime")
    }

    fun onFenceExit(fenceId: String, fenceName: String, exitTime: Date) {
        val session = activeSessions.remove(fenceId)

        // 【关键防御】即使围栏不在 activeSessions 中，也要关闭 DB 中的开放记录
        // 防止开放记录残留导致重启后重复计时
        if (session == null) {
            Log.w(TAG, "围栏 $fenceName 不在 activeSessions 中，尝试关闭 DB 开放记录")
            val updatedRows = dbHelper.closeOpenStayRecord(fenceId, exitTime, 0L)
            Log.w(TAG, "围栏 $fenceName DB 开放记录关闭结果: updatedRows=$updatedRows")
            return
        }

        val enterDateKey = session.dateKey
        val exitDateKey = getDateKey(exitTime)
        val totalDuration = (exitTime.time - session.enterTime.time) / 1000

        // 【修复】关闭该围栏的开放记录，而不是插入新记录
        // 这样可以避免重复记录：open record 被关闭，不会残留
        if (totalDuration > 0) {
            val updatedRows = dbHelper.closeOpenStayRecord(fenceId, exitTime, totalDuration)
            if (updatedRows == 0) {
                // 没有找到开放记录（可能已被其他方式关闭），则插入新记录作为兜底
                val stayRecord = FenceStayRecord(
                    fenceId = fenceId,
                    fenceName = fenceName,
                    enterTime = session.enterTime,
                    exitTime = exitTime,
                    durationSeconds = totalDuration
                )
                dbHelper.insertStayRecord(stayRecord)
                Log.w(TAG, "未找到开放记录，创建新记录: $fenceName, 时长=${totalDuration}秒")
            } else {
                Log.d(TAG, "关闭开放记录: $fenceName, 时长=${totalDuration}秒")
            }
        }

        if (enterDateKey == exitDateKey) {
            val duration = (exitTime.time - session.enterTime.time) / 1000
            if (duration > 0) {
                addDailyDuration(enterDateKey, fenceId, fenceName, duration)
                Log.d(TAG, "结束计时: $fenceName, 日期=$enterDateKey, 时长=${duration}秒")
            }
        } else {
            // 跨天情况：分别计算每天的停留时长

            // ===== 第一天：从进入时间到当天午夜 =====
            // 正确做法：获取进入日期的午夜（次日0点）
            val enterCalendar = Calendar.getInstance()
            enterCalendar.time = session.enterTime
            // 设为次日0点0分0秒0毫秒
            enterCalendar.add(Calendar.DAY_OF_MONTH, 1)
            enterCalendar.set(Calendar.HOUR_OF_DAY, 0)
            enterCalendar.set(Calendar.MINUTE, 0)
            enterCalendar.set(Calendar.SECOND, 0)
            enterCalendar.set(Calendar.MILLISECOND, 0)
            val midnightOfNextDay = enterCalendar.time

            val durationDay1 = (midnightOfNextDay.time - session.enterTime.time) / 1000
            if (durationDay1 > 0) {
                addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                Log.d(TAG, "跨天计时(首日): $fenceName, 日期=$enterDateKey, 时长=${durationDay1}秒")
            }

            // ===== 中间完整天：每整天计 86400 秒 =====
            // 从次日0点开始
            val dayCalendar = Calendar.getInstance()
            dayCalendar.time = midnightOfNextDay

            // 退出日期的午夜
            val exitCalendar = Calendar.getInstance()
            exitCalendar.time = exitTime
            exitCalendar.set(Calendar.HOUR_OF_DAY, 0)
            exitCalendar.set(Calendar.MINUTE, 0)
            exitCalendar.set(Calendar.SECOND, 0)
            exitCalendar.set(Calendar.MILLISECOND, 0)

            // 计算中间完整天（不含首尾）
            while (dayCalendar.before(exitCalendar)) {
                val fullDayKey = getDateKey(dayCalendar.time)
                addDailyDuration(fullDayKey, fenceId, fenceName, 86400L)
                Log.d(TAG, "跨天计时(整天): $fenceName, 日期=$fullDayKey, 时长=86400秒")
                dayCalendar.add(Calendar.DAY_OF_MONTH, 1)
            }

            // ===== 最后一天：从当天午夜到退出时间 =====
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

        // Update the active session's accumulated duration
        activeSessions[fenceId]?.let { session ->
            activeSessions[fenceId] = session.copy(
                accumulatedDuration = newDuration,
                lastSaveTime = Date()
            )
        }

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

    /**
     * 按月统计（包含子围栏时长汇总到父围栏）
     * @param year 年
     * @param month 月
     * @param parentMap 父围栏映射 { childId: parentId }
     */
    fun getMonthlyRecordsWithAggregation(year: Int, month: Int, parentMap: Map<String, String>): Map<String, Long> {
        val base = getMonthlyRecords(year, month)
        return aggregateToParents(base, parentMap)
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

    /**
     * 按年统计（包含子围栏时长汇总到父围栏）
     * @param year 年
     * @param parentMap 父围栏映射 { childId: parentId }
     */
    fun getYearlyRecordsWithAggregation(year: Int, parentMap: Map<String, String>): Map<String, Long> {
        val base = getYearlyRecords(year)
        return aggregateToParents(base, parentMap)
    }

    /**
     * 将子围栏的时长汇总到父围栏
     * @param base 基础统计 map { fenceId: durationSeconds }
     * @param parentMap 父围栏映射 { childId: parentId }
     */
    private fun aggregateToParents(base: Map<String, Long>, parentMap: Map<String, String>): Map<String, Long> {
        // 构建 child -> [children] 的反向映射
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        for ((childId, parentId) in parentMap) {
            childrenOf.getOrPut(parentId) { mutableListOf() }.add(childId)
        }

        // 对每个有子围栏的父围栏，累加所有子围栏的时长
        val result = base.toMutableMap()
        fun addChildrenToParent(parentId: String) {
            val children = childrenOf[parentId] ?: return
            val parentDuration = result[parentId] ?: 0L
            var accumulated = parentDuration
            for (childId in children) {
                val childDuration = result[childId] ?: 0L
                accumulated += childDuration
                // 递归累加更深层的子围栏
                addChildrenToParent(childId)
            }
            result[parentId] = accumulated
        }

        // 对所有父围栏执行累加
        for (parentId in childrenOf.keys) {
            addChildrenToParent(parentId)
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

    /**
     * 将子围栏的时长转移到父围栏
     * 当子围栏退出且父围栏仍在匹配中时调用
     * @param childFenceId 子围栏ID
     * @param parentFenceId 父围栏ID
     * @param childExitTime 子围栏的退出时间
     */
    fun transferChildDurationToParent(childFenceId: String, parentFenceId: String, childExitTime: Date) {
        val childSession = activeSessions[childFenceId] ?: return
        val parentSession = activeSessions[parentFenceId] ?: return

        // 计算gap：父围栏从上次保存到子围栏退出的时间（子围栏活动期间，父围栏累积的时长）
        val gapDuration = if (parentSession.lastSaveTime != null) {
            (childExitTime.time - parentSession.lastSaveTime.time) / 1000
        } else {
            0L
        }

        // 父围栏的新累积时长 = 原累积 + gap（父围栏在子围栏活动期间的时间）+ 子围栏的累积时长
        val newAccumulated = parentSession.accumulatedDuration + gapDuration + childSession.accumulatedDuration

        // 更新父围栏session：累积时长增加，lastSaveTime更新为子围栏退出时间（父围栏重新变成active的时间点）
        activeSessions[parentFenceId] = parentSession.copy(
            accumulatedDuration = newAccumulated,
            lastSaveTime = childExitTime
        )

        // 移除子围栏session（时长已转移到父围栏）
        activeSessions.remove(childFenceId)
        Log.d(TAG, "转移子围栏时长到父围栏: $childFenceId → $parentFenceId, gap=${gapDuration}秒, child累加=${childSession.accumulatedDuration}秒, 父新累积=${newAccumulated}秒")
    }

    /**
     * 当子围栏进入时，冻结父围栏的累积时长
     * 这样父围栏的累积时长不会在子围栏活动期间被错误累加
     * @param parentFenceId 父围栏ID
     * @param childEnterTime 子围栏进入时间
     */
    fun updateParentOnChildEnter(parentFenceId: String, childEnterTime: Date) {
        val parentSession = activeSessions[parentFenceId] ?: return
        // 【关键修复】冻结父围栏的累积时长 = 此刻的 getCurrentDuration
        // 这样子围栏活动期间 getCurrentDuration 不会累加父围栏时长
        // 而是只计算 (now - childEnterTime)，即 0
        val currentTotal = getCurrentDurationInternal(parentFenceId)
        activeSessions[parentFenceId] = parentSession.copy(
            accumulatedDuration = currentTotal,
            lastSaveTime = childEnterTime
        )
        Log.d(TAG, "子围栏进入，冻结父围栏 $parentFenceId 累积时长=${currentTotal}秒，lastSaveTime=${childEnterTime}")
    }

    /**
     * 内部方法：计算围栏从lastSaveTime到当前的时长（不更新任何状态）
     */
    private fun getCurrentDurationInternal(fenceId: String): Long {
        val session = activeSessions[fenceId] ?: return 0L
        return if (session.lastSaveTime != null) {
            (System.currentTimeMillis() - session.lastSaveTime.time) / 1000
        } else {
            (System.currentTimeMillis() - session.enterTime.time) / 1000
        }
    }

    fun getCurrentDuration(fenceId: String): Long? {
        val session = activeSessions[fenceId] ?: return null
        val currentDuration = (System.currentTimeMillis() - session.enterTime.time) / 1000

        // If this is a restored session with accumulated duration from before restart
        // (indicated by accumulatedDuration > 0 and lastSaveTime == null),
        // we need to account for the gap between app close and reopen.
        // The gap is approximately: currentDuration - accumulatedDuration (time since last save)
        // If the fence was outside during the gap, actual current = accumulated + (current - accumulated) = current
        // But we want: accumulated + post-gap time
        // Since we don't have lastSaveTime, we estimate: actual current = accumulated + max(0, currentDuration - accumulated)
        if (session.accumulatedDuration > 0 && session.lastSaveTime == null) {
            // This session was restored after app restart
            // accumulatedDuration is the duration at the time of the last save before close
            // We need to add only the time since the last save (post-gap time)
            // Since we don't have lastSaveTime, we estimate post-gap time as:
            // currentDuration - accumulatedDuration (time elapsed since accumulated was recorded)
            val postGapTime = maxOf(0L, currentDuration - session.accumulatedDuration)
            return session.accumulatedDuration + postGapTime
        }

        // For sessions with lastSaveTime (active sessions that have been updated via addDailyDuration),
        // the accumulatedDuration already includes time up to lastSaveTime
        if (session.lastSaveTime != null) {
            val timeSinceLastSave = (System.currentTimeMillis() - session.lastSaveTime.time) / 1000
            return session.accumulatedDuration + timeSinceLastSave
        }

        return currentDuration
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

        // Load today's daily records into dailyCache to restore accumulated duration
        val todayKey = formatDate(Date())
        val todayRecords = dbHelper.getDailyStayRecordsByDate(todayKey)
        for (record in todayRecords) {
            val dateRecords = dailyCache.getOrPut(record.date) { mutableMapOf() }
            dateRecords[record.fenceId] = record
            Log.d(TAG, "恢复日统计: ${record.fenceName}, 日期=${record.date}, 时长=${record.totalDurationSeconds}秒")
        }

        for (stay in openStays) {
            // 暂时恢复所有开放记录为活动会话
            // 嵌套围栏的父子关系会在 handleFirstLocationAfterRestoreInternal 中处理
            val accumulatedDuration = stay.durationSeconds ?: 0L
            val session = FenceActiveSession(
                fenceId = stay.fenceId,
                fenceName = stay.fenceName,
                enterTime = stay.enterTime,
                dateKey = getDateKey(stay.enterTime),
                accumulatedDuration = accumulatedDuration,
                lastSaveTime = null
            )
            activeSessions[stay.fenceId] = session
            Log.d(TAG, "恢复活动会话: ${stay.fenceName}, 累积时长=${accumulatedDuration}秒")
        }

        // 【关键】延迟清理嵌套围栏：在 handleFirstLocationAfterRestoreInternal 完成后再调用
        // 这里先标记需要清理的父围栏，等 Application 完全初始化后处理
    }

    /**
     * 延迟清理孤立父围栏会话
     * 在所有开放记录恢复后调用，关闭那些有活动子围栏的父围栏会话
     * 因为 App 重启后 fenceTreeManager 已初始化，此时可以正确判断父子关系
     */
    fun cleanupOrphanedParentSessions() {
        val app = context.applicationContext as? TrackMasterApplication ?: return
        val treeManager = app.fenceTreeManager

        Log.d(TAG, "=== cleanupOrphanedParentSessions 开始 ===")
        Log.d(TAG, "    activeSessions 数量: ${activeSessions.size}")
        for (session in activeSessions.values) {
            val parentId = treeManager.getParent(session.fenceId)
            Log.d(TAG, "    会话: ${session.fenceName} (${session.fenceId}), parentId: ${parentId ?: "null"}")
        }

        val toRemove = mutableListOf<String>()
        for (session in activeSessions.values) {
            // 检查是否有子围栏也在 activeSessions 中
            val hasChildSession = activeSessions.values.any { other ->
                other.fenceId != session.fenceId &&
                treeManager.getParent(other.fenceId) == session.fenceId
            }
            if (hasChildSession) {
                Log.w(TAG, "发现孤立父围栏: ${session.fenceName} (有子围栏在活动)")
                toRemove.add(session.fenceId)
            }
        }

        // 如果父子关系都没找到， fallback：保留最新进入的围栏，关闭其他的
        if (toRemove.isEmpty() && activeSessions.size > 1) {
            Log.w(TAG, "父子关系未找到，使用 fallback：保留最新进入的围栏")
            val latest = activeSessions.values.maxByOrNull { it.enterTime }
            for (session in activeSessions.values) {
                if (session.fenceId != latest?.fenceId) {
                    toRemove.add(session.fenceId)
                }
            }
        }

        for (fenceId in toRemove) {
            val removed = activeSessions.remove(fenceId)
            if (removed != null) {
                // 关闭 DB 中的开放记录
                val updatedRows = dbHelper.closeOpenStayRecord(fenceId, Date(), removed.accumulatedDuration)
                Log.w(TAG, "关闭孤立围栏 DB 记录: ${removed.fenceName}, updatedRows=$updatedRows")
            }
        }
        Log.d(TAG, "=== cleanupOrphanedParentSessions 结束，保留 ${activeSessions.size} 个会话 ===")
    }

    fun saveAllActiveSessions() {
        Log.d(TAG, "保存所有活动会话, 数量=${activeSessions.size}")
        val openStays = dbHelper.getOpenStayRecords()
        val openFenceIds = openStays.map { it.fenceId }.toSet()
        val now = System.currentTimeMillis()

        for (session in activeSessions.values) {
            // 计算当前累积时长：到lastSaveTime的累积时长 + (now - lastSaveTime)
            // 这样save后，如果app重启，getCurrentDuration()会返回正确的累积时长
            val currentTotalDuration = (now - session.enterTime.time) / 1000
            val sessionAccumulated = if (session.lastSaveTime != null) {
                session.accumulatedDuration + (now - session.lastSaveTime.time) / 1000
            } else {
                currentTotalDuration
            }

            // Also update daily_stays with the current accumulated duration
            // This ensures dailyCache has the correct accumulated duration for getStatistics()
            val dailyRecord = DailyStayRecord(
                date = session.dateKey,
                fenceId = session.fenceId,
                fenceName = session.fenceName,
                totalDurationSeconds = sessionAccumulated,
                lastUpdated = Date()
            )
            saveDailyRecord(dailyRecord)

            if (session.fenceId !in openFenceIds) {
                // 新围栏：创建开放记录，duration存储累积时长
                val stayRecord = FenceStayRecord(
                    fenceId = session.fenceId,
                    fenceName = session.fenceName,
                    enterTime = session.enterTime,
                    exitTime = null,
                    durationSeconds = sessionAccumulated
                )
                dbHelper.insertStayRecord(stayRecord)
                Log.d(TAG, "创建开放停留记录: ${session.fenceName}, 累积时长=${sessionAccumulated}秒")
            } else {
                // 已有开放记录：更新累积时长
                dbHelper.updateOpenStayAccumulatedDuration(session.fenceId, sessionAccumulated)
            }

            // 更新本地session的累积时长和lastSaveTime
            activeSessions[session.fenceId] = session.copy(
                accumulatedDuration = sessionAccumulated,
                lastSaveTime = Date()
            )
        }
    }
}
