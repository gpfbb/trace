package com.trackmaster.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.data.FenceStayRecord
import java.lang.ref.WeakReference
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

    // Background thread for periodic saving to avoid main-thread DB writes
    private val saveThread = HandlerThread("StayDurationSaver").apply { start() }
    private val periodicSaveHandler = Handler(saveThread.looper)
    private var saveRunnable: PeriodicSaveRunnable? = null

    /**
     * 【问题8修复】静态内部类 + WeakReference 避免 Handler 内存泄漏
     * 原问题：非静态内部类 saveRunnable 持有外部类 StayDurationManager 引用
     * 导致 Activity/Context 无法被 GC 回收
     */
    private class PeriodicSaveRunnable(managerRef: WeakReference<StayDurationManager>) : Runnable {
        private val managerRef = managerRef

        override fun run() {
            val manager = managerRef.get() ?: return
            if (manager.activeSessions.isNotEmpty()) {
                AppLog.d(TAG, "定时保存活跃会话: ${manager.activeSessions.size}个")
                manager.saveAllActiveSessions()
            }
            manager.periodicSaveHandler.postDelayed(this, PERIODIC_SAVE_INTERVAL_MS)
        }
    }

    init {
        AppLog.d(TAG, "停留时长管理器初始化")
        loadActiveSessionsFromDatabase()

        // 启动定时保存：每5分钟自动保存一次活跃会话到数据库
        // 这样即使App被后台清理或强制关闭，已累积的时长也会被保存
        saveRunnable = PeriodicSaveRunnable(WeakReference(this))
        saveRunnable?.let { periodicSaveHandler.postDelayed(it, PERIODIC_SAVE_INTERVAL_MS) }
    }
    fun getDateKey(date: Date): String {
        return formatDate(date)
    }

    private val enterLock = Any()

    fun onFenceEnter(fenceId: String, fenceName: String, enterTime: Date) {
        val dateKey = getDateKey(enterTime)

        synchronized(enterLock) {
            // 如果已在 activeSessions 中，先关闭旧会话
            val existing = activeSessions[fenceId]
            if (existing != null) {
                AppLog.w(TAG, "围栏 $fenceName 已在 activeSessions 中，先关闭旧会话再启动新会话")
                val now = System.currentTimeMillis()
                val accumulated = if (existing.lastSaveTime != null) {
                    existing.accumulatedDuration + (now - existing.lastSaveTime.time) / 1000
                } else {
                    existing.accumulatedDuration
                }
                if (accumulated > 0) {
                    dbHelper.closeOpenStayRecord(fenceId, Date(), accumulated)
                }
            }

            // 关闭数据库中已有该围栏的未关闭记录
            val existingOpenStays = dbHelper.getOpenStayRecords().filter { it.fenceId == fenceId }
            for (oldStay in existingOpenStays) {
                val totalDuration = (enterTime.time - oldStay.enterTime.time) / 1000
                dbHelper.insertStayRecord(FenceStayRecord(
                    fenceId = oldStay.fenceId,
                    fenceName = oldStay.fenceName,
                    enterTime = oldStay.enterTime,
                    exitTime = enterTime,
                    durationSeconds = totalDuration
                ))
                AppLog.w(TAG, "关闭异常开放记录: $fenceName, 进入=${oldStay.enterTime}, 异常退出=$enterTime, 累积时长=${totalDuration}秒")
            }

            val session = FenceActiveSession(
                fenceId = fenceId,
                fenceName = fenceName,
                enterTime = enterTime,
                dateKey = dateKey
            )
            activeSessions[fenceId] = session

            // 立即创建开放记录
            dbHelper.insertStayRecord(FenceStayRecord(
                fenceId = fenceId,
                fenceName = fenceName,
                enterTime = enterTime,
                exitTime = null,
                durationSeconds = 0L
            ))
        }

        AppLog.d(TAG, "开始计时: $fenceName, 日期=$dateKey, 时间=$enterTime")
    }

    fun onFenceExit(fenceId: String, fenceName: String, exitTime: Date) {
        synchronized(enterLock) {
            onFenceExitLocked(fenceId, fenceName, exitTime)
        }
    }

    private fun onFenceExitLocked(fenceId: String, fenceName: String, exitTime: Date) {
        val session = activeSessions.remove(fenceId)

        // 【关键防御】即使围栏不在 activeSessions 中，也要关闭 DB 中的开放记录
        if (session == null) {
            AppLog.w(TAG, "围栏 $fenceName 不在 activeSessions 中，尝试关闭 DB 开放记录")
            val existingOpen = dbHelper.getOpenStayRecords().filter { it.fenceId == fenceId }.firstOrNull()
            val duration = existingOpen?.durationSeconds ?: 0L
            val updatedRows = dbHelper.closeOpenStayRecord(fenceId, exitTime, duration)
            AppLog.w(TAG, "围栏 $fenceName DB 开放记录关闭结果: updatedRows=$updatedRows, 实际时长=${duration}秒")
            return
        }

        val enterDateKey = session.dateKey
        val exitDateKey = getDateKey(exitTime)
        val now = System.currentTimeMillis()

        val sessionAccumulated = if (session.lastSaveTime != null) {
            session.accumulatedDuration + (now - session.lastSaveTime.time) / 1000
        } else {
            val effectiveGapStart = session.gapStartTime ?: session.enterTime
            session.accumulatedDuration + (now - effectiveGapStart.time) / 1000
        }

        // 先检查父围栏是否仍在 active，再决定是否关闭子围栏 DB 记录
        val app = context.applicationContext as? TrackMasterApplication
        val parentId = app?.fenceTreeManager?.getParent(fenceId)
        val parentStillActive = parentId != null && activeSessions.containsKey(parentId)

        if (parentStillActive) {
            AppLog.d(TAG, "子围栏 $fenceName 的时长已合并到父围栏，跳过 DB 关闭和 daily 写入")
        } else {
            // 关闭该围栏的开放记录
            if (sessionAccumulated > 0) {
                val updatedRows = dbHelper.closeOpenStayRecord(fenceId, exitTime, sessionAccumulated)
                if (updatedRows == 0) {
                    dbHelper.insertStayRecord(FenceStayRecord(
                        fenceId = fenceId,
                        fenceName = fenceName,
                        enterTime = session.enterTime,
                        exitTime = exitTime,
                        durationSeconds = sessionAccumulated
                    ))
                    AppLog.w(TAG, "未找到开放记录，创建新记录: $fenceName, 时长=${sessionAccumulated}秒")
                } else {
                    AppLog.d(TAG, "关闭开放记录: $fenceName, 时长=${sessionAccumulated}秒")
                }
            }

            if (enterDateKey == exitDateKey) {
                if (sessionAccumulated > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, sessionAccumulated)
                    AppLog.d(TAG, "结束计时: $fenceName, 日期=$enterDateKey, 时长=${sessionAccumulated}秒")
                }
            } else {
                // 跨天情况
                val enterCalendar = Calendar.getInstance()
                enterCalendar.time = session.enterTime
                enterCalendar.add(Calendar.DAY_OF_MONTH, 1)
                enterCalendar.set(Calendar.HOUR_OF_DAY, 0)
                enterCalendar.set(Calendar.MINUTE, 0)
                enterCalendar.set(Calendar.SECOND, 0)
                enterCalendar.set(Calendar.MILLISECOND, 0)
                val midnightOfNextDay = enterCalendar.time

                val durationDay1 = (midnightOfNextDay.time - session.enterTime.time) / 1000
                if (durationDay1 > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                    AppLog.d(TAG, "跨天计时(首日): $fenceName, 日期=$enterDateKey, 时长=${durationDay1}秒")
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
                    val fullDayKey = getDateKey(dayCalendar.time)
                    addDailyDuration(fullDayKey, fenceId, fenceName, 86400L)
                    AppLog.d(TAG, "跨天计时(整天): $fenceName, 日期=$fullDayKey, 时长=86400秒")
                    dayCalendar.add(Calendar.DAY_OF_MONTH, 1)
                }

                val durationLastDay = (exitTime.time - exitCalendar.time.time) / 1000
                if (durationLastDay > 0) {
                    addDailyDuration(exitDateKey, fenceId, fenceName, durationLastDay)
                    AppLog.d(TAG, "跨天计时(末日): $fenceName, 日期=$exitDateKey, 时长=${durationLastDay}秒")
                }
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

        // 【P0修复】使用原子 replace 操作避免 read-modify-write 竞态
        // 注意：对于子围栏已经转移到父围栏的情况，这个更新不会影响已转移的子围栏会话
        activeSessions[fenceId]?.let { s ->
            val updated = s.copy(
                accumulatedDuration = s.accumulatedDuration + safeDuration,
                lastSaveTime = Date()
            )
            activeSessions.replace(fenceId, s, updated)
        }

        AppLog.d(TAG, "更新日统计: $fenceName, 日期=$dateKey, 总时长=${newDuration}秒")
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
        val visited = mutableSetOf<String>()
        fun addChildrenToParent(parentId: String) {
            if (!visited.add(parentId)) return // 循环引用保护
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
            AppLog.e(TAG, "警告: 单日总时长 ${total}秒 超过24小时 (${maxDailySeconds}秒)!")
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
        AppLog.d(TAG, "转移子围栏时长到父围栏: $childFenceId → $parentFenceId, child累加=${childSession.accumulatedDuration}秒, 父新累积=${newAccumulated}秒")
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
        AppLog.d(TAG, "子围栏进入，冻结父围栏 $parentFenceId 累积时长=${currentTotal}秒，parentAccumulatedSnapshotAtChildEnter=${currentTotal}秒，lastSaveTime=${childEnterTime}")
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

        // 【Bug修复】lastSaveTime == null 表示刚恢复的会话（gap 已通过第一次 saveAllActiveSessions 加入 accumulated）
        // lastSaveTime != null 表示已 save 过的会话：accumulated + elapsed since last save
        // gapStartTime 不再用于计算，因为 gap 只在第一次 save 时加一次，之后 lastSaveTime 就不为 null 了
        if (session.lastSaveTime != null) {
            val timeSinceLastSave = (System.currentTimeMillis() - session.lastSaveTime.time) / 1000
            return session.accumulatedDuration + timeSinceLastSave
        }

        // 刚恢复的会话（lastSaveTime == null）：accumulated 已包含 gap 时长，直接返回
        // 正常新会话：从未保存过，返回从 enterTime 到现在（clamp 到 0 防止系统时钟回拨）
        return maxOf(0L, (System.currentTimeMillis() - session.enterTime.time) / 1000)
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
            AppLog.d(TAG, "恢复日统计: ${record.fenceName}, 日期=${record.date}, 时长=${record.totalDurationSeconds}秒")
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
            AppLog.d(TAG, "恢复活动会话: ${stay.fenceName}, 累积时长=${accumulatedDuration}秒")
        }
    }

    /**
     * 延迟清理孤立父围栏会话
     */
    fun cleanupOrphanedParentSessions() {
        val app = context.applicationContext as? TrackMasterApplication ?: return
        val treeManager = app.fenceTreeManager

        AppLog.d(TAG, "=== cleanupOrphanedParentSessions 开始 ===")
        AppLog.d(TAG, "    activeSessions 数量: ${activeSessions.size}")
        for (session in activeSessions.values) {
            val parentId = treeManager.getParent(session.fenceId)
            AppLog.d(TAG, "    会话: ${session.fenceName} (${session.fenceId}), parentId: ${parentId ?: "null"}")
        }

        val toRemove = mutableListOf<String>()
        for (session in activeSessions.values) {
            val hasChildSession = activeSessions.values.any { other ->
                other.fenceId != session.fenceId &&
                treeManager.getParent(other.fenceId) == session.fenceId
            }
            if (hasChildSession) {
                AppLog.w(TAG, "发现孤立父围栏: ${session.fenceName} (有子围栏在活动)")
                toRemove.add(session.fenceId)
            }
        }

        if (toRemove.isEmpty() && activeSessions.size > 1) {
            AppLog.w(TAG, "父子关系未找到，使用 fallback：保留最新进入的围栏")
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
                AppLog.w(TAG, "关闭孤立围栏 DB 记录: ${removed.fenceName}, updatedRows=$updatedRows")
            }
        }
        AppLog.d(TAG, "=== cleanupOrphanedParentSessions 结束，保留 ${activeSessions.size} 个会话 ===")
    }

    fun saveAllActiveSessions() {
        // 使用快照避免迭代期间 ConcurrentHashMap 被并发修改
        val snapshot = activeSessions.toList()
        AppLog.d(TAG, "保存所有活动会话, 数量=${snapshot.size}")
        val openStays = dbHelper.getOpenStayRecords()
        val openFenceIds = openStays.map { it.fenceId }.toSet()
        val now = System.currentTimeMillis()

        for ((fenceId, session) in snapshot) {
            val sessionAccumulated = if (session.lastSaveTime != null) {
                session.accumulatedDuration + (now - session.lastSaveTime.time) / 1000
            } else {
                val gapStart = session.gapStartTime ?: session.enterTime
                session.accumulatedDuration + (now - gapStart.time) / 1000
            }

            if (session.fenceId !in openFenceIds) {
                AppLog.w(TAG, "saveAllActiveSessions 发现无开放记录的会话，跳过: ${session.fenceName}")
                continue
            }
            try {
                dbHelper.updateOpenStayAccumulatedDuration(session.fenceId, sessionAccumulated)
                // 使用 replace 原子操作：只有会话未被并发修改/删除时才更新
                val updated = session.copy(
                    accumulatedDuration = sessionAccumulated,
                    lastSaveTime = Date()
                )
                activeSessions.replace(fenceId, session, updated)
            } catch (e: Exception) {
                AppLog.e(TAG, "DB更新失败，跳过此围栏: ${session.fenceName}", e)
            }
        }
    }

    /**
     * 清理 Handler，防止内存泄漏
     * 当 StayDurationManager 需要被重建时调用
     */
    fun destroy() {
        AppLog.d(TAG, "停留时长管理器销毁，清理 Handler")
        saveRunnable?.let { periodicSaveHandler.removeCallbacks(it) }
        saveRunnable = null
        saveThread.quitSafely()
    }
}
