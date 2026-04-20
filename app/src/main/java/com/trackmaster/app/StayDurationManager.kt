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
    val lastSaveTime: Date? = null,
    // 记录子围栏进入时父围栏的累积时长，用于精确计算父围栏在子围栏退出后的新增时长
    val parentAccumulatedSnapshotAtChildEnter: Long? = null,
    // App关闭时刻，用于崩溃恢复后正确计算gap时长
    val gapStartTime: Date? = null
)

class StayDurationManager(private val context: Context) {
    companion object {
        private const val TAG = "StayDurationManager"
        private const val PERIODIC_SAVE_INTERVAL_MS = 5 * 60 * 1000L // 5分钟
        private val DATE_FORMAT_THREAD_LOCAL = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("yyyy-MM-dd", Locale.US)
            }
        }

        fun formatDate(date: Date): String {
            return DATE_FORMAT_THREAD_LOCAL.get()?.format(date) ?: ""
        }
    }

    private val dbHelper by lazy { (context.applicationContext as TrackMasterApplication).dbHelper }
    private val persistentStateManager by lazy { (context.applicationContext as TrackMasterApplication).persistentStateManager }

    public val activeSessions: ConcurrentHashMap<String, FenceActiveSession> = ConcurrentHashMap()
    private val dailyCache = ConcurrentHashMap<String, MutableMap<String, DailyStayRecord>>()

    // Handler for periodic saving (leak prevention: keep reference to remove callbacks on destroy)
    private val periodicSaveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = object : Runnable {
        override fun run() {
            if (activeSessions.isNotEmpty()) {
                Log.d(TAG, "定时保存活跃会话: ${activeSessions.size}个")
                saveAllActiveSessions()
            }
            periodicSaveHandler.postDelayed(this, PERIODIC_SAVE_INTERVAL_MS)
        }
    }

    init {
        Log.d(TAG, "停留时长管理器初始化")
        loadActiveSessionsFromDatabase()

        // 启动定时保存：每5分钟自动保存一次活跃会话到数据库
        // 这样即使App被后台清理或强制关闭，已累积的时长也会被保存
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
            // 【Bug修复】从 DB 查询实际累积时长，而非硬编码 0L
            val existingOpen = dbHelper.getOpenStayRecords().filter { it.fenceId == fenceId }.firstOrNull()
            val duration = existingOpen?.durationSeconds ?: 0L
            val updatedRows = dbHelper.closeOpenStayRecord(fenceId, exitTime, duration)
            Log.w(TAG, "围栏 $fenceName DB 开放记录关闭结果: updatedRows=$updatedRows, 实际时长=${duration}秒")
            return
        }

        val enterDateKey = session.dateKey
        val exitDateKey = getDateKey(exitTime)
        val now = System.currentTimeMillis()

        // 【关键修复】使用 sessionAccumulated 而非 raw (exitTime - enterTime)
        // sessionAccumulated 已经包含了 child transfer 等所有时间，
        // 而 raw duration 对于有子围栏的场景会重复累加 child 时间导致统计错误
        val sessionAccumulated = if (session.lastSaveTime != null) {
            session.accumulatedDuration + (now - session.lastSaveTime.time) / 1000
        } else {
            // 【Bug修复】恢复的会话: accumulatedDuration 已包含之前的累积，gap 应从 gapStartTime 算起（而非 enterTime）
            // 错误原因: enterTime 是原始进入时间（如8天前），用它计算 gap 会导致 pre-gap 时长被重复累加
            // 正确做法: gapStartTime 记录的是 App 关闭时刻（gap 开始），用 (now - gapStartTime) 计算 gap 时长
            val effectiveGapStart = session.gapStartTime ?: session.enterTime
            session.accumulatedDuration + (now - effectiveGapStart.time) / 1000
        }

        // 关闭该围栏的开放记录，避免残留
        if (sessionAccumulated > 0) {
            val updatedRows = dbHelper.closeOpenStayRecord(fenceId, exitTime, sessionAccumulated)
            if (updatedRows == 0) {
                val stayRecord = FenceStayRecord(
                    fenceId = fenceId,
                    fenceName = fenceName,
                    enterTime = session.enterTime,
                    exitTime = exitTime,
                    durationSeconds = sessionAccumulated
                )
                dbHelper.insertStayRecord(stayRecord)
                Log.w(TAG, "未找到开放记录，创建新记录: $fenceName, 时长=${sessionAccumulated}秒")
            } else {
                Log.d(TAG, "关闭开放记录: $fenceName, 时长=${sessionAccumulated}秒")
            }
        }

        // 【Bug修复 - 技术债】检查子围栏退出时父围栏是否仍在 activeSessions
        // 如果是，说明子围栏的时长已通过 transferChildDurationToParent 合并到父围栏，
        // 子围栏不应再独立写入 daily_stays，避免双重计数
        //
        // 【技术债告警】parentStillActive 只检查退出时刻parent是否active，
        // 不反映子围栏活跃期间parent的状态变化（如parent退出又重新进入）。
        // 正确修复需要引入时间段概念：记录父子围栏各自的活动时间段，
        // 检测子围栏活跃期间与父围栏活动时间的重叠关系。
        // 当前逻辑在某些边界情况下可能误判，但影响有限（仅限父子围栏都active的场景）。
        val app = context.applicationContext as? TrackMasterApplication
        val parentId = app?.fenceTreeManager?.getParent(fenceId)
        val parentStillActive = parentId != null && activeSessions.containsKey(parentId)
        if (parentStillActive) {
            Log.d(TAG, "子围栏 $fenceName 的时长已合并到父围栏，跳过独立写入")
        }

        if (enterDateKey == exitDateKey) {
            if (sessionAccumulated > 0 && !parentStillActive) {
                addDailyDuration(enterDateKey, fenceId, fenceName, sessionAccumulated)
                Log.d(TAG, "结束计时: $fenceName, 日期=$enterDateKey, 时长=${sessionAccumulated}秒")
            }
        } else {
            // 跨天情况：分别计算每天的停留时长（使用 sessionAccumulated 替代 raw duration）
            val enterCalendar = Calendar.getInstance()
            enterCalendar.time = session.enterTime
            enterCalendar.add(Calendar.DAY_OF_MONTH, 1)
            enterCalendar.set(Calendar.HOUR_OF_DAY, 0)
            enterCalendar.set(Calendar.MINUTE, 0)
            enterCalendar.set(Calendar.SECOND, 0)
            enterCalendar.set(Calendar.MILLISECOND, 0)
            val midnightOfNextDay = enterCalendar.time

            val durationDay1 = (midnightOfNextDay.time - session.enterTime.time) / 1000
            if (durationDay1 > 0 && !parentStillActive) {
                addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                Log.d(TAG, "跨天计时(首日): $fenceName, 日期=$enterDateKey, 时长=${durationDay1}秒")
            }

            val dayCalendar = Calendar.getInstance()
            dayCalendar.time = midnightOfNextDay

            val exitCalendar = Calendar.getInstance()
            exitCalendar.time = exitTime
            exitCalendar.set(Calendar.HOUR_OF_DAY, 0)
            exitCalendar.set(Calendar.MINUTE, 0)
            exitCalendar.set(Calendar.SECOND, 0)
            exitCalendar.set(Calendar.MILLISECOND, 0)

            while (dayCalendar.before(exitCalendar)) {
                if (!parentStillActive) {
                    val fullDayKey = getDateKey(dayCalendar.time)
                    addDailyDuration(fullDayKey, fenceId, fenceName, 86400L)
                    Log.d(TAG, "跨天计时(整天): $fenceName, 日期=$fullDayKey, 时长=86400秒")
                }
                dayCalendar.add(Calendar.DAY_OF_MONTH, 1)
            }

            val durationLastDay = (exitTime.time - exitCalendar.time.time) / 1000
            if (durationLastDay > 0 && !parentStillActive) {
                addDailyDuration(exitDateKey, fenceId, fenceName, durationLastDay)
                Log.d(TAG, "跨天计时(末日): $fenceName, 日期=$exitDateKey, 时长=${durationLastDay}秒")
            }
        }
    }


    private fun addDailyDuration(dateKey: String, fenceId: String, fenceName: String, durationSeconds: Long) {
        // 【Bug修复】P7-C-BUG005：对超过24小时的单日时长进行截断
        val safeDuration = minOf(durationSeconds, 24 * 60 * 60L)

        val existing = getDailyRecord(dateKey, fenceId)
        val newDuration = if (existing != null) {
            existing.totalDurationSeconds + safeDuration
        } else {
            safeDuration
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
        // 注意：对于子围栏已经转移到父围栏的情况，这个更新不会影响已转移的子围栏会话
        activeSessions[fenceId]?.let { s ->
            activeSessions[fenceId] = s.copy(
                accumulatedDuration = s.accumulatedDuration + safeDuration,
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
     */
    fun getYearlyRecordsWithAggregation(year: Int, parentMap: Map<String, String>): Map<String, Long> {
        val base = getYearlyRecords(year)
        return aggregateToParents(base, parentMap)
    }

    /**
     * 将子围栏的时长汇总到父围栏
     */
    private fun aggregateToParents(base: Map<String, Long>, parentMap: Map<String, String>): Map<String, Long> {
        val childrenOf = mutableMapOf<String, MutableList<String>>()
        for ((childId, parentId) in parentMap) {
            childrenOf.getOrPut(parentId) { mutableListOf() }.add(childId)
        }

        val result = base.toMutableMap()
        fun addChildrenToParent(parentId: String) {
            val children = childrenOf[parentId] ?: return
            val parentDuration = result[parentId] ?: 0L
            var accumulated = parentDuration
            for (childId in children) {
                val childDuration = result[childId] ?: 0L
                accumulated += childDuration
                addChildrenToParent(childId)
            }
            result[parentId] = accumulated
        }

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
     *
     * 时长计算逻辑：
     * - 父围栏在子围栏进入前的累积时长：parentAccumulatedSnapshotAtChildEnter
     * - 父围栏在子围栏活跃期间的时间（冻结的）：parentAccumulatedSnapshotAtChildEnter（因为updateParentOnChildEnter冻结了时间）
     * - 子围栏的时长：childSession.accumulatedDuration
     *
     * 在子围栏退出时，父围栏的 accumulated = 父在子进入前的时长 + 子围栏活跃期间父"冻结"的时长（=子进入时刻的 enterTime 到子退出时刻的差值）
     * 由于 updateParentOnChildEnter 已经把父围栏的 accumulated 冻结为 (childEnterTime - parent.enterTime)，
     * 所以 transfer 后父围栏的 accumulated 正确等于：父在子进入前的时长 + 子围栏活跃的时长 = 父围栏在子围栏退出时的总时长
     *
     * 关键：transferChildDurationToParent 不需要加 gapDuration，因为 accumulated 已经在 updateParentOnChildEnter 时被正确设置
     */
    fun transferChildDurationToParent(childFenceId: String, parentFenceId: String, childExitTime: Date) {
        val childSession = activeSessions[childFenceId] ?: return
        val parentSession = activeSessions[parentFenceId] ?: return

        // 父围栏新累积 = 父围栏当前累积 + 子围栏累积
        // 父围栏当前累积（来自 updateParentOnChildEnter 的冻结值）= 父围栏在子围栏进入前的时长
        // 因此新累积 = 父围栏在子围栏进入前的时长 + 子围栏的时长 = 父围栏在子围栏退出时的正确总时长
        val newAccumulated = parentSession.accumulatedDuration + childSession.accumulatedDuration

        // 更新父围栏session
        activeSessions[parentFenceId] = parentSession.copy(
            accumulatedDuration = newAccumulated,
            lastSaveTime = childExitTime
        )

        // 移除子围栏session
        activeSessions.remove(childFenceId)
        Log.d(TAG, "转移子围栏时长到父围栏: $childFenceId → $parentFenceId, child累加=${childSession.accumulatedDuration}秒, 父新累积=${newAccumulated}秒")
    }

    /**
     * 当子围栏进入时，冻结父围栏的累积时长
     *
     * 原理：冻结后 getCurrentDurationInternal 返回 (now - childEnterTime)，
     * 这个值等于子围栏进入时刻父围栏已经累积的时长（因为从父围栏进入到现在的时间差 = 子围栏进入时刻的父围栏总时长）。
     * 之后在 transferChildDurationToParent 中加子围栏的时长，就得到父围栏在子围栏退出时的正确总时长。
     */
    fun updateParentOnChildEnter(parentFenceId: String, childEnterTime: Date) {
        val parentSession = activeSessions[parentFenceId] ?: return
        // 冻结父围栏的累积时长为此刻的 getCurrentDuration
        val currentTotal = getCurrentDurationInternal(parentFenceId)
        // 同时记录此刻的累积时长作为快照，用于 transfer 时精确计算
        activeSessions[parentFenceId] = parentSession.copy(
            accumulatedDuration = currentTotal,
            lastSaveTime = childEnterTime,
            parentAccumulatedSnapshotAtChildEnter = currentTotal
        )
        Log.d(TAG, "子围栏进入，冻结父围栏 $parentFenceId 累积时长=${currentTotal}秒，parentAccumulatedSnapshotAtChildEnter=${currentTotal}秒，lastSaveTime=${childEnterTime}")
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
        val now = System.currentTimeMillis()

        // 【Bug修复】对于崩溃恢复的会话（gapStartTime != null && lastSaveTime == null）
        // 正确计算: accumulatedDuration(纯活跃时长) + (now - gapStartTime)(gap期间，App未运行)
        // 注意: gapStartTime 在 loadActiveSessionsFromDatabase 时被设为重启时刻，而非真实崩溃时刻
        // 这是 Android 的固有限制，gapDuration 会在重启后略大于真实 gap（重启到崩溃之间的时长）
        if (session.gapStartTime != null && session.lastSaveTime == null) {
            val gapDuration = (now - session.gapStartTime.time) / 1000
            return session.accumulatedDuration + gapDuration
        }

        if (session.lastSaveTime != null) {
            val timeSinceLastSave = (now - session.lastSaveTime.time) / 1000
            return session.accumulatedDuration + timeSinceLastSave
        }

        // 正常新会话：从未保存过，直接返回从 enterTime 到现在
        return (now - session.enterTime.time) / 1000
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

        // Load today's daily records into dailyCache
        val todayKey = formatDate(Date())
        val todayRecords = dbHelper.getDailyStayRecordsByDate(todayKey)
        for (record in todayRecords) {
            val dateRecords = dailyCache.getOrPut(record.date) { mutableMapOf() }
            dateRecords[record.fenceId] = record
            Log.d(TAG, "恢复日统计: ${record.fenceName}, 日期=${record.date}, 时长=${record.totalDurationSeconds}秒")
        }

        for (stay in openStays) {
            val accumulatedDuration = stay.durationSeconds ?: 0L
            val session = FenceActiveSession(
                fenceId = stay.fenceId,
                fenceName = stay.fenceName,
                enterTime = stay.enterTime,
                dateKey = getDateKey(stay.enterTime),
                accumulatedDuration = accumulatedDuration,
                lastSaveTime = null,
                // 【Bug修复】gapStartTime 应设为 lastAppCloseTime（App 真正关闭的时刻）
                // 而非 Date()（重启时刻），否则 gap 时长会被计算为重启到当前的时间而非关闭到当前的时间
                // 如果 persistentStateManager 没有 lastAppCloseTime 记录（App 未正常关闭就崩溃），则 fallback 到 Date()
                gapStartTime = persistentStateManager.getLastAppCloseTime() ?: Date()
            )
            activeSessions[stay.fenceId] = session
            Log.d(TAG, "恢复活动会话: ${stay.fenceName}, 累积时长=${accumulatedDuration}秒")
        }
    }

    /**
     * 延迟清理孤立父围栏会话
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
            val hasChildSession = activeSessions.values.any { other ->
                other.fenceId != session.fenceId &&
                treeManager.getParent(other.fenceId) == session.fenceId
            }
            if (hasChildSession) {
                Log.w(TAG, "发现孤立父围栏: ${session.fenceName} (有子围栏在活动)")
                toRemove.add(session.fenceId)
            }
        }

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
            val sessionAccumulated = if (session.lastSaveTime != null) {
                session.accumulatedDuration + (now - session.lastSaveTime.time) / 1000
            } else {
                val gapStart = session.gapStartTime ?: session.enterTime
                session.accumulatedDuration + (now - gapStart.time) / 1000
            }

            // 【关键修复】只更新 stays 表的已有开放记录，不插入新行
            // 防止多次 periodic save 创建多条开放记录导致 stays 表求和时 2x 叠加
            // onFenceEnter/onFenceExit 会负责创建和关闭记录，periodic save 只负责更新累积时长
            if (session.fenceId in openFenceIds) {
                // 【P1修复】DB更新成功后才能更新in-memory状态
                // 原bug：DB更新在try内但in-memory在try外，DB失败时in-memory仍被更新
                // 导致crash后lastSaveTime已是新值，但gap计算基准未变，造成时长重复
                try {
                    dbHelper.updateOpenStayAccumulatedDuration(session.fenceId, sessionAccumulated)
                    // DB成功后才更新in-memory
                    activeSessions[session.fenceId] = session.copy(
                        accumulatedDuration = sessionAccumulated,
                        lastSaveTime = Date(),
                        gapStartTime = Date()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "DB更新失败，跳过此围栏: ${session.fenceName}", e)
                    // 不更新in-memory，保持旧状态，下次重试
                }
            } else {
                Log.w(TAG, "saveAllActiveSessions 发现无开放记录的会话，跳过: ${session.fenceName}")
            }
        }
    }

    /**
     * 清理 Handler，防止内存泄漏
     * 当 StayDurationManager 需要被重建时调用
     */
    fun destroy() {
        Log.d(TAG, "停留时长管理器销毁，清理 Handler")
        periodicSaveHandler.removeCallbacks(saveRunnable)
    }
}
