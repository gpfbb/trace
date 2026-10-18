package com.trackmaster.app.core

import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.data.database.DatabaseFacade
import com.trackmaster.app.locationservice.Location
// 【Bug修复】统一使用 StayDurationManager.kt 中的 7 字段版本 FenceActiveSession
// （原 core 包下有一个只有 4 个字段的同名 data class，因本地声明优先而遮蔽了本 import，
//  导致 accumulatedDuration / lastSaveTime / gapStartTime 在 put 进 activeSessions 时全部丢失）
import com.trackmaster.app.FenceActiveSession
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.model.Category
import com.trackmaster.app.throttle.ThrottleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date

/**
 * 围栏处理核心逻辑
 *
 * 包含所有围栏检测、计时、状态恢复相关逻辑。
 * 由 TrackMasterApplication 持有，在定位服务回调中调用。
 */
class FenceProcessingHolder(
    private val engineHolder: EngineHolder,
    private val repositoryHolder: RepositoryHolder,
    private val managersHolder: ManagersHolder,
    private val fenceTreeManager: FenceTreeManager,
    private val dbHelper: DatabaseFacade,
    private val appScope: CoroutineScope,
    private val onNotifyFenceStateChanged: () -> Unit
) {
    private val TAG = "FenceProcessing"

    // ---- 代理引用（避免每次跨类调用）----
    private val engine get() = engineHolder.engine
    private val repository get() = repositoryHolder.repository
    private val geoFences get() = repositoryHolder.geoFences
    private val geoFenceEvents get() = repositoryHolder.geoFenceEvents
    private val fenceEnterTimes get() = repositoryHolder.fenceEnterTimes
    private val stayDurationManager get() = managersHolder.stayDurationManager
    private val persistentStateManager get() = managersHolder.persistentStateManager

    // ---- 内部状态 ----
    @Volatile private var isRestoringFirstLocation = false
    private val previousSmallestFenceId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    // ============================================================
    // 公开 API
    // ============================================================

    fun checkGeoFences(location: Location, accel: AccelerometerData? = null): Boolean {
        val fix = com.trackmaster.app.core.LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.timestamp,
        )

        persistentStateManager.saveLastLocation(location.latitude, location.longitude, Date(location.timestamp))
        // 【Bug修复】传递 accel 数据，使 SmartScheduler 静止判定能使用加速度数据
        // 在无加速度数据时（accel=null），仍可正常工作
        val shouldProcess = engine.evaluateLocation(fix, accel)
        // 【Bug修复 H1+H3】删除外部 setCurrentMatchedFenceIds 调用
        // 原因：onLocationUpdate (appScope.launch 内部) 会用动态 bufferM 更新 currentMatchedFenceIds
        // 外部 checkFenceMatch(bufferM=0.0) 先写入，onLocationUpdate 后写入 → TOCTOU 竞态
        // 围栏状态以 onLocationUpdate 结果为准，外部调用删除

        // 【P0修复】只有 shouldProcess=true 时才执行围栏匹配计算
        // Throttled GPS 回调不应触发昂贵的空间计算，否则会导致每分钟30+次无意义的CPU开销
        if (!shouldProcess) {
            return false
        }

        // 【P0修复】在 evaluateLocation 时捕获围栏状态快照，避免 TOCTOU 竞态
        // 原问题：evaluateLocation 和 onLocationUpdate 之间存在时间窗口，
        // 另一线程可能修改 currentMatchedFenceIds
        // 修复：在 checkGeoFences 入口处捕获快照，后续操作使用快照值
        val matchedFenceIdsSnapshot = engine.currentMatchedFenceIds.toList()

        // 【P0修复】不要在主线程同步写 currentMatchedFences
        // checkGeoFences 中的 sync 写入和 handleAllMatchedFences 中的 async 写入存在竞态
        // 统一由 handleAllMatchedFences 在 appScope.launch 中更新，onNotifyFenceStateChanged 触发 UI 刷新

        appScope.launch {
            // 调度决策已在上面由 evaluateLocation 完成，这里只做位置处理，
            // 不再重复调用 SmartScheduler（详见 Engine.processApprovedLocation 注释）
            val closedSeg = engine.processApprovedLocation(fix)
            // 【Bug修复】handleFenceExit 需要使用检测到exit时的matched fence状态，
            // 而不是 onLocationUpdate 之后的最新状态（可能导致TOCTOU竞态）
            // 使用上面捕获的快照
            if (closedSeg != null) {
                handleFenceExit(closedSeg.leafGeofenceId, matchedFenceIdsSnapshot, location)
            }
            handleAllMatchedFences(location, closedSeg?.leafGeofenceId, fix)
        }

        return shouldProcess
    }

    fun handleFirstLocationAfterRestore(location: Location) {
        if (isRestoringFirstLocation) return
        appScope.launch {
            isRestoringFirstLocation = true
            try {
                handleFirstLocationInternal(location)
            } finally {
                isRestoringFirstLocation = false
            }
        }
    }

    fun saveStateOnAppClose() {
        val currentTime = Date()
        persistentStateManager.saveLastAppCloseTime(currentTime)
        stayDurationManager.saveAllActiveSessions()
        AppLog.d(TAG, "保存应用关闭状态: $currentTime")
    }

    fun getVacuumDuration(): Long? {
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime() ?: return null
        val currentTime = System.currentTimeMillis()
        val vacuumDuration = (currentTime - lastAppCloseTime.time) / 1000
        return if (vacuumDuration > 0) vacuumDuration else null
    }

    fun getLastExitFence(): com.trackmaster.app.PersistentStateManager.LastExitState? {
        return persistentStateManager.getLastExitState()
    }

    fun refreshEngineFences() {
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        AppLog.d(TAG, "刷新Engine: 核心围栏数=${coreFences.size}")
        engineHolder.refresh(coreFences)
        onNotifyFenceStateChanged()
    }

    fun checkAndUpdateFenceState() {
        val lastLoc = persistentStateManager.getLastLocation() ?: return
        val fix = com.trackmaster.app.core.LocationFix(
            lat = lastLoc.latitude,
            lng = lastLoc.longitude,
            accuracy = 30f,  // 恢复检查用保守容差，围栏通常比30米大,
            timestamp = lastLoc.time.time
        )
        val matchedIds = engine.checkFenceMatch(fix)
        val smallestFenceId = matchedIds.firstOrNull()

        // 【P0修复】无论是否在围栏内，都要更新 currentMatchedFences 状态
        // 若不在围栏内（matchedIds为空），需清除旧状态，否则UI显示stale数据
        repositoryHolder.currentMatchedFences = emptyList()
        if (smallestFenceId == null) {
            // 用户已离开所有围栏，清除状态并通知UI刷新
            onNotifyFenceStateChanged()
            return
        }

        val fence = geoFences.find { it.id == smallestFenceId }
        val fenceName = fence?.name ?: smallestFenceId
        val enterTs = System.currentTimeMillis()
        val enterTime = Date(enterTs)

        fenceEnterTimes[smallestFenceId] = enterTime
        // 【P0修复】使用 putIfAbsent 原子操作避免 check-then-act 竞态
        val existingSession = stayDurationManager.activeSessions.putIfAbsent(
            smallestFenceId,
            FenceActiveSession(
                fenceId = smallestFenceId,
                fenceName = fenceName,
                enterTime = enterTime,
                dateKey = com.trackmaster.app.StayDurationManager.formatDate(enterTime)
            )
        )
        if (existingSession != null) {
            // 另一线程已插入，直接使用现有session（不做重复创建）
        }
        persistentStateManager.saveCurrentFenceState(smallestFenceId, fenceName, enterTime)
        engine.setCurrentFenceForRecovery(smallestFenceId, enterTs)

        repositoryHolder.currentMatchedFences = matchedIds.mapNotNull { fenceId -> geoFences.find { it.id == fenceId } }
        onNotifyFenceStateChanged()
    }

    // ============================================================
    // 内部方法
    // ============================================================

    private fun handleFenceExit(fenceId: String, matchedFenceIdsSnapshot: List<String>, location: Location) {
        recordFenceEvent(fenceId, fenceId, GeoFenceAction.EXIT, location)
        stayDurationManager.onFenceExit(fenceId, fenceId, Date(location.timestamp))
        fenceEnterTimes.remove(fenceId)

        val parentId = fenceTreeManager.getParent(fenceId)
        // 【Bug修复】使用捕获时的matched fence状态快照，避免TOCTOU竞态
        if (parentId != null && matchedFenceIdsSnapshot.contains(parentId)) {
            stayDurationManager.transferChildDurationToParent(fenceId, parentId, Date(location.timestamp))
        }
        persistentStateManager.saveLastExitState(fenceId, fenceId, Date(location.timestamp))
        persistentStateManager.clearCurrentFenceState()
    }

    private fun handleAllMatchedFences(location: Location, justExitedFenceId: String?, fix: LocationFix) {
        // 使用 checkFenceMatch 而非 engine.currentMatchedFenceIds
        // 确保与 checkGeoFences 同步更新使用相同的 buffer 策略
        val allMatchedFenceIds = engine.checkFenceMatchWithDynamicBuffer(fix)
        if (allMatchedFenceIds.isEmpty()) {
            if (!isRestoringFirstLocation) {
                // 【Bug修复】清除所有围栏的进入时间，防止 stale 状态
                // fenceEnterTimes 记录各围栏的进入时间，围栏为空时必须全量清空
                fenceEnterTimes.keys.toList().forEach { fenceEnterTimes.remove(it) }
                repositoryHolder.currentMatchedFences = emptyList()
                onNotifyFenceStateChanged()
            }
            return
        }

        if (!isRestoringFirstLocation) {
            repositoryHolder.currentMatchedFences = allMatchedFenceIds.mapNotNull { fenceId -> geoFences.find { it.id == fenceId } }
            onNotifyFenceStateChanged()
        }

        val smallestFenceId = allMatchedFenceIds.firstOrNull()
        detectFenceTransition(smallestFenceId, allMatchedFenceIds, location)

        if (smallestFenceId != null) {
            previousSmallestFenceId.set(smallestFenceId)
        }

        for (fenceId in allMatchedFenceIds) {
            if (fenceId == justExitedFenceId) continue
            if (fenceEnterTimes.containsKey(fenceId)) continue

            val fence = geoFences.find { it.id == fenceId }
            val fenceName = fence?.name ?: fenceId
            AppLog.d(TAG, "进入围栏: $fenceName")

            recordFenceEvent(fenceId, fenceName, GeoFenceAction.ENTER, location)

            if (fenceId == smallestFenceId) {
                val parentId = fenceTreeManager.getParent(fenceId)
                if (parentId != null) {
                    stayDurationManager.updateParentOnChildEnter(parentId, Date(location.timestamp))
                }
                if (!stayDurationManager.isFenceActive(fenceId)) {
                    stayDurationManager.onFenceEnter(fenceId, fenceName, Date(location.timestamp))
                    persistentStateManager.saveCurrentFenceState(fenceId, fenceName, Date(location.timestamp))
                }
            }
            fenceEnterTimes[fenceId] = Date(location.timestamp)
        }
    }

    private fun detectFenceTransition(smallestFenceId: String?, allMatchedFenceIds: List<String>, location: Location) {
        val previousId = previousSmallestFenceId.get()
        if (previousId == null || previousId == smallestFenceId) return

        // 【Bug修复 H2】子→父升级 vs 同级切换的区分逻辑
        // case A→B（同级切换，same parent）：prevParent==currParent，两者都还在 allMatchedFenceIds → 不 exit
        // case A→P（子→父升级）：prevParent是P，currParent是P的parent → prevParent!=currParent
        //   此时 previousId(A) 仍在 allMatchedFenceIds（因为A的polygon被P的polygon包含）→ 需要 exit A
        // case A→Q（完全无关）：previousId 不在 allMatchedFenceIds → 需要 exit A
        if (previousId in allMatchedFenceIds) {
            val prevParent = fenceTreeManager.getParent(previousId)
            val currParent = smallestFenceId?.let { fenceTreeManager.getParent(it) }
            // 同级切换：两者有相同的直接父级（prevParent == currParent）→ 用户还在同一父围栏内，不 exit
            // 子→父升级：prevParent != currParent → previousId 已在 allMatchedFenceIds，需要 exit
            if (prevParent != null && prevParent == currParent) {
                // 同级 sibling 切换，不 exit
                return
            }
            // 子→父升级：prevParent != currParent → fall through，执行 exit
        }

        val oldFenceName = geoFences.find { it.id == previousId }?.name ?: previousId ?: ""
        if (oldFenceName.isEmpty() || !stayDurationManager.isFenceActive(previousId)) return

        AppLog.d(TAG, "强制退出旧围栏(围栏转换检测): $oldFenceName")
        stayDurationManager.onFenceExit(previousId, oldFenceName, Date(location.timestamp))
        fenceEnterTimes.remove(previousId)
        val oldParentId = fenceTreeManager.getParent(previousId)
        if (oldParentId != null && allMatchedFenceIds.contains(oldParentId)) {
            stayDurationManager.transferChildDurationToParent(previousId, oldParentId, Date(location.timestamp))
        }
    }

    /**
     * 【问题9修复】拆分为多个小函数，每个处理一种场景
     */
    private suspend fun handleFirstLocationInternal(location: Location) {
        val currentFenceState = persistentStateManager.getCurrentFenceState()
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime()
        val fix = com.trackmaster.app.core.LocationFix(
            lat = location.latitude, lng = location.longitude,
            accuracy = location.accuracy, timestamp = location.timestamp,
        )

        AppLog.d(TAG, "首次定位处理: 位置=(${fix.lat}, ${fix.lng}), 围栏数=${geoFences.size}")

        val vacuumStartTs = lastAppCloseTime?.time
        val engineResult = engine.initWithLocation(fix, vacuumStartTs)
        val matchedIds = engine.checkFenceMatch(fix)

        // 场景1：用户仍在原围栏内，恢复计时
        if (shouldRestoreOriginalFence(engineResult, currentFenceState, matchedIds)) {
            restoreFenceTiming(engineResult.fenceId ?: currentFenceState?.fenceId, fix.timestamp, location, matchedIds)
            return
        }

        // 场景2：用户已离开原围栏，关闭旧计时
        if (currentFenceState != null) {
            closeOldFenceTiming(currentFenceState, lastAppCloseTime, location)
        }

        // 场景3：用户在新围栏内，开始新计时
        if (matchedIds.isNotEmpty()) {
            AppLog.d(TAG, "用户在围栏 ${matchedIds.first()} 内，开始新计时")
            startFenceTimingFromRestore(matchedIds.first(), fix.timestamp, location, matchedIds)
        } else {
            engine.setCurrentFenceForRecovery(null, fix.timestamp)
        }
    }

    /**
     * 判断是否应该恢复原围栏计时
     */
    private fun shouldRestoreOriginalFence(
        engineResult: com.trackmaster.app.core.Engine.RestoreResult,
        currentFenceState: com.trackmaster.app.PersistentStateManager.CurrentFenceState?,
        matchedIds: List<String>
    ): Boolean {
        // 【P1修复】改OR为AND：Engine说不要恢复时，即使matchedIds匹配也不恢复
        val stillInOriginalFence = (engineResult.shouldRestore && engineResult.fenceId != null && matchedIds.contains(engineResult.fenceId)) &&
                (currentFenceState == null || matchedIds.contains(currentFenceState.fenceId))
        return stillInOriginalFence
    }

    /**
     * 恢复原围栏计时
     */
    private suspend fun restoreFenceTiming(
        fenceId: String?,
        enterTs: Long,
        location: Location,
        matchedIds: List<String>
    ) {
        if (fenceId == null) {
            engine.setCurrentFenceForRecovery(null, location.timestamp)
            return
        }

        // 优先用 stayDurationManager 中已有 session 的 enterTime（保留了 accumulatedDuration）
        if (stayDurationManager.activeSessions.containsKey(fenceId)) {
            val session = stayDurationManager.activeSessions[fenceId]
            if (session != null) {
                AppLog.d(TAG, "恢复围栏计时（使用已有session）: ${session.fenceName}, 进入时间=${session.enterTime}, 累积时长=${session.accumulatedDuration}秒")
                engine.setCurrentFenceForRecovery(fenceId, session.enterTime.time)
                return
            }
        }

        // 没有已有session，创建新session
        AppLog.d(TAG, "Engine检测到未关闭Segment，但没有已有session，创建新记录")
        startFenceTimingFromRestore(fenceId, enterTs, location, matchedIds)
    }

    /**
     * 关闭旧围栏计时
     */
    private fun closeOldFenceTiming(
        currentFenceState: com.trackmaster.app.PersistentStateManager.CurrentFenceState,
        lastAppCloseTime: Date?,
        location: Location
    ) {
        val exitTime = lastAppCloseTime ?: Date(location.timestamp)
        stayDurationManager.onFenceExit(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
        fenceEnterTimes.remove(currentFenceState.fenceId)
        persistentStateManager.saveLastExitState(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
        persistentStateManager.clearCurrentFenceState()
        AppLog.d(TAG, "已关闭旧围栏 ${currentFenceState.fenceName} 的计时")
    }

    private fun updateApplicationStateForFence(fenceId: String, enterTs: Long, location: Location) {
        val fence = geoFences.find { it.id == fenceId }
        val fenceName = fence?.name ?: fenceId
        val enterTime = Date(enterTs)

        AppLog.d(TAG, "更新围栏状态: $fenceName, 进入时间=$enterTime")
        fenceEnterTimes[fenceId] = enterTime

        // 【P0修复】使用 putIfAbsent 原子操作避免 check-then-act 竞态
        stayDurationManager.activeSessions.putIfAbsent(
            fenceId,
            FenceActiveSession(
                fenceId = fenceId, fenceName = fenceName,
                enterTime = enterTime,
                dateKey = com.trackmaster.app.StayDurationManager.formatDate(enterTime)
            )
        )
        persistentStateManager.saveCurrentFenceState(fenceId, fenceName, enterTime)
        repositoryHolder.currentMatchedFences = geoFences.filter { it.id == fenceId }
        onNotifyFenceStateChanged()
    }

    private fun startFenceTimingFromRestore(fenceId: String?, enterTs: Long, location: Location, matchedFenceIds: List<String>) {
        if (fenceId == null) {
            engine.setCurrentFenceForRecovery(null, location.timestamp)
            return
        }
        val fence = geoFences.find { it.id == fenceId }
        val fenceName = fence?.name ?: fenceId
        val enterTime = Date(enterTs)

        AppLog.d(TAG, "开始围栏计时: $fenceName, 进入时间=$enterTime")
        if (stayDurationManager.isFenceActive(fenceId)) {
            stayDurationManager.onFenceExit(fenceId, fenceName, enterTime)
        }
        recordFenceEvent(fenceId, fenceName, GeoFenceAction.ENTER, location)
        stayDurationManager.onFenceEnter(fenceId, fenceName, enterTime)
        fenceEnterTimes[fenceId] = enterTime
        persistentStateManager.saveCurrentFenceState(fenceId, fenceName, enterTime)
        repositoryHolder.currentMatchedFences = matchedFenceIds.mapNotNull { id -> geoFences.find { it.id == id } }
        engine.setCurrentFenceForRecovery(fenceId, enterTs)
        onNotifyFenceStateChanged()
    }

    private fun recordFenceEvent(fenceId: String, fenceName: String, action: GeoFenceAction, location: Location) {
        val event = GeoFenceEvent(
            fenceId = fenceId, fenceName = fenceName, action = action,
            time = Date(location.timestamp), latitude = location.latitude,
            longitude = location.longitude, accuracy = location.accuracy
        )
        repositoryHolder.addEvent(event)  // 【Bug修复 H12】通过带cap的addEvent方法添加，自动清理超限事件
        dbHelper.insertEvent(event)
        AppLog.d(TAG, "记录围栏事件: ${action.name} - $fenceName")
    }
}
