package com.trackmaster.app.core

import android.util.Log
import com.trackmaster.app.GeoFenceDatabaseHelper
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.FenceActiveSession
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.model.Category
import com.trackmaster.app.services.Aggregator
import com.trackmaster.app.utils.ThrottleConfig
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
    private val dbHelper: GeoFenceDatabaseHelper,
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
    private val currentMatchedFences get() = repositoryHolder.currentMatchedFences
    private val stayDurationManager get() = managersHolder.stayDurationManager
    private val persistentStateManager get() = managersHolder.persistentStateManager

    // ---- 内部状态 ----
    private var isRestoringFirstLocation = false
    private val previousSmallestFenceId = java.util.concurrent.atomic.AtomicReference<String?>(null)

    // ============================================================
    // 公开 API
    // ============================================================

    fun checkGeoFences(location: Location): Boolean {
        val fix = com.trackmaster.app.core.LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.timestamp,
        )

        persistentStateManager.saveLastLocation(location.latitude, location.longitude, Date(location.timestamp))
        val shouldProcess = engine.evaluateLocation(fix)
        engine.setCurrentMatchedFenceIds(engine.checkFenceMatch(fix))

        // 【关键修复】立即同步更新 currentMatchedFences 和通知 UI 变化
        // 让 HomeViewModel 等组件能立即感知围栏状态变化，而不是等待异步处理完成
        val allMatchedFenceIds = engine.currentMatchedFenceIds
        currentMatchedFences.clear()
        for (fenceId in allMatchedFenceIds) {
            val fence = geoFences.find { it.id == fenceId }
            if (fence != null) currentMatchedFences.add(fence)
        }
        onNotifyFenceStateChanged()

        appScope.launch {
            val (closedSeg, _) = engine.onLocationUpdate(fix)
            if (closedSeg != null) {
                handleFenceExit(closedSeg.leafGeofenceId, closedSeg.leafGeofenceId, location)
            }
            handleAllMatchedFences(location, closedSeg?.leafGeofenceId)
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
        Log.d(TAG, "保存应用关闭状态: $currentTime")
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
        Log.d(TAG, "刷新Engine: 核心围栏数=${coreFences.size}")
        engineHolder.refresh(coreFences)
        onNotifyFenceStateChanged()
    }

    fun checkAndUpdateFenceState() {
        val lastLoc = persistentStateManager.getLastLocation() ?: return
        val fix = com.trackmaster.app.core.LocationFix(
            lat = lastLoc.latitude,
            lng = lastLoc.longitude,
            accuracy = 50f,
            timestamp = lastLoc.time.time
        )
        val matchedIds = engine.checkFenceMatch(fix)
        val smallestFenceId = matchedIds.firstOrNull() ?: return

        val fence = geoFences.find { it.id == smallestFenceId }
        val fenceName = fence?.name ?: smallestFenceId
        val enterTs = System.currentTimeMillis()
        val enterTime = Date(enterTs)

        fenceEnterTimes[smallestFenceId] = enterTime
        if (!stayDurationManager.activeSessions.containsKey(smallestFenceId)) {
            stayDurationManager.activeSessions[smallestFenceId] = FenceActiveSession(
                fenceId = smallestFenceId,
                fenceName = fenceName,
                enterTime = enterTime,
                dateKey = com.trackmaster.app.StayDurationManager.formatDate(enterTime)
            )
        }
        persistentStateManager.saveCurrentFenceState(smallestFenceId, fenceName, enterTime)
        engine.setCurrentFenceForRecovery(smallestFenceId, enterTs)

        currentMatchedFences.clear()
        for (fenceId in matchedIds) {
            val f = geoFences.find { it.id == fenceId }
            if (f != null) currentMatchedFences.add(f)
        }
        if (matchedIds.isNotEmpty()) {
            onNotifyFenceStateChanged()
        }
    }

    // ============================================================
    // 内部方法
    // ============================================================

    private fun handleFenceExit(fenceId: String, fenceName: String, location: Location) {
        recordFenceEvent(fenceId, fenceName, GeoFenceAction.EXIT, location)
        stayDurationManager.onFenceExit(fenceId, fenceName, Date(location.timestamp))
        fenceEnterTimes.remove(fenceId)

        val parentId = fenceTreeManager.getParent(fenceId)
        if (parentId != null && engine.currentMatchedFenceIds.contains(parentId)) {
            stayDurationManager.transferChildDurationToParent(fenceId, parentId, Date(location.timestamp))
        }
        persistentStateManager.saveLastExitState(fenceId, fenceName, Date(location.timestamp))
        persistentStateManager.clearCurrentFenceState()
    }

    private fun handleAllMatchedFences(location: Location, justExitedFenceId: String?) {
        val allMatchedFenceIds = engine.currentMatchedFenceIds
        if (allMatchedFenceIds.isEmpty()) {
            if (!isRestoringFirstLocation) {
                currentMatchedFences.clear()
                onNotifyFenceStateChanged()
            }
            return
        }

        if (!isRestoringFirstLocation) {
            currentMatchedFences.clear()
            for (fenceId in allMatchedFenceIds) {
                val fence = geoFences.find { it.id == fenceId }
                if (fence != null) currentMatchedFences.add(fence)
            }
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
            Log.d(TAG, "进入围栏: $fenceName")

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

        // Bug 4 修复：如果 previousId 仍在 matched 列表中，检查是否是 sibling 切换
        if (previousId in allMatchedFenceIds) {
            val prevParent = fenceTreeManager.getParent(previousId)
            val currParent = fenceTreeManager.getParent(smallestFenceId ?: "")
            // sibling 切换：同一父级的子围栏之间切换，继续处理 exit
            if (prevParent != null && prevParent == currParent) {
                // 继续执行 exit 处理
            } else {
                return
            }
        }

        val oldFenceName = geoFences.find { it.id == previousId }?.name ?: previousId ?: ""
        if (oldFenceName.isEmpty() || !stayDurationManager.isFenceActive(previousId)) return

        Log.d(TAG, "强制退出旧围栏(围栏转换检测): $oldFenceName")
        stayDurationManager.onFenceExit(previousId, oldFenceName, Date(location.timestamp))
        fenceEnterTimes.remove(previousId)
        val oldParentId = fenceTreeManager.getParent(previousId)
        if (oldParentId != null && allMatchedFenceIds.contains(oldParentId)) {
            stayDurationManager.transferChildDurationToParent(previousId, oldParentId, Date(location.timestamp))
        }
    }

    private suspend fun handleFirstLocationInternal(location: Location) {
        val currentFenceState = persistentStateManager.getCurrentFenceState()
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime()
        val fix = com.trackmaster.app.core.LocationFix(
            lat = location.latitude, lng = location.longitude,
            accuracy = location.accuracy, timestamp = location.timestamp,
        )

        Log.d(TAG, "首次定位处理: 位置=(${fix.lat}, ${fix.lng}), 围栏数=${geoFences.size}")

        val vacuumStartTs = lastAppCloseTime?.time
        val engineResult = engine.initWithLocation(fix, vacuumStartTs)

        // 【关键修复】统一用 stayDurationManager 的 accumulatedDuration 主导恢复逻辑
        // 避免 currentFenceState.enterTime（原始enter）和 accumulatedDuration 两条线分开走导致时长 x2
        val matchedIds = engine.checkFenceMatch(fix)

        // 用户仍在原围栏内（Engine数据库有记录 或 PersistentStateManager有记录 或 matchedIds匹配）
        val stillInOriginalFence = (engineResult.shouldRestore && engineResult.fenceId != null && matchedIds.contains(engineResult.fenceId)) ||
                (currentFenceState != null && matchedIds.contains(currentFenceState.fenceId))

        if (stillInOriginalFence) {
            // 优先用 stayDurationManager 中已有 session 的 enterTime（保留了 accumulatedDuration）
            // activeSessions 在 loadActiveSessionsFromDatabase() 时已从 DB 恢复
            val fenceId = engineResult.fenceId ?: currentFenceState?.fenceId
            if (fenceId != null && stayDurationManager.activeSessions.containsKey(fenceId)) {
                val session = stayDurationManager.activeSessions[fenceId]!!
                Log.d(TAG, "恢复围栏计时（使用已有session）: ${session.fenceName}, 进入时间=${session.enterTime}, 累积时长=${session.accumulatedDuration}秒")
                engine.setCurrentFenceForRecovery(fenceId, session.enterTime.time)
            } else {
                // 没有已有session，走 startFenceTimingFromRestore 创建新session
                Log.d(TAG, "Engine检测到未关闭Segment，但没有已有session，创建新记录")
                startFenceTimingFromRestore(engineResult.fenceId ?: currentFenceState?.fenceId, fix.timestamp, location, matchedIds)
            }
            return
        }

        // 用户不在原围栏内 → 关闭旧围栏，开始新计时
        if (currentFenceState != null) {
            val exitTime = lastAppCloseTime ?: Date(location.timestamp)
            stayDurationManager.onFenceExit(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
            fenceEnterTimes.remove(currentFenceState.fenceId)
            persistentStateManager.saveLastExitState(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
            persistentStateManager.clearCurrentFenceState()
            Log.d(TAG, "已关闭旧围栏 ${currentFenceState.fenceName} 的计时")
        }

        if (matchedIds.isNotEmpty()) {
            Log.d(TAG, "用户在围栏 ${matchedIds.first()} 内，开始新计时")
            startFenceTimingFromRestore(matchedIds.first(), fix.timestamp, location, matchedIds)
        } else {
            engine.setCurrentFenceForRecovery(null, fix.timestamp)
        }
    }

    private fun updateApplicationStateForFence(fenceId: String, enterTs: Long, location: Location) {
        val fence = geoFences.find { it.id == fenceId }
        val fenceName = fence?.name ?: fenceId
        val enterTime = Date(enterTs)

        Log.d(TAG, "更新围栏状态: $fenceName, 进入时间=$enterTime")
        fenceEnterTimes[fenceId] = enterTime

        if (!stayDurationManager.activeSessions.containsKey(fenceId)) {
            stayDurationManager.activeSessions[fenceId] = FenceActiveSession(
                fenceId = fenceId, fenceName = fenceName,
                enterTime = enterTime,
                dateKey = com.trackmaster.app.StayDurationManager.formatDate(enterTime)
            )
        }
        persistentStateManager.saveCurrentFenceState(fenceId, fenceName, enterTime)
        currentMatchedFences.clear()
        val fenceObj = geoFences.find { it.id == fenceId }
        if (fenceObj != null) currentMatchedFences.add(fenceObj)
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

        Log.d(TAG, "开始围栏计时: $fenceName, 进入时间=$enterTime")
        if (stayDurationManager.isFenceActive(fenceId)) {
            stayDurationManager.onFenceExit(fenceId, fenceName, enterTime)
        }
        recordFenceEvent(fenceId, fenceName, GeoFenceAction.ENTER, location)
        stayDurationManager.onFenceEnter(fenceId, fenceName, enterTime)
        fenceEnterTimes[fenceId] = enterTime
        persistentStateManager.saveCurrentFenceState(fenceId, fenceName, enterTime)
        currentMatchedFences.clear()
        for (id in matchedFenceIds) {
            val f = geoFences.find { it.id == id }
            if (f != null) currentMatchedFences.add(f)
        }
        engine.setCurrentFenceForRecovery(fenceId, enterTs)
        onNotifyFenceStateChanged()
    }

    private fun recordFenceEvent(fenceId: String, fenceName: String, action: GeoFenceAction, location: Location) {
        val event = GeoFenceEvent(
            fenceId = fenceId, fenceName = fenceName, action = action,
            time = Date(location.timestamp), latitude = location.latitude,
            longitude = location.longitude, accuracy = location.accuracy
        )
        geoFenceEvents.add(event)
        dbHelper.insertEvent(event)
        Log.d(TAG, "记录围栏事件: ${action.name} - $fenceName")
    }
}

// 用于 StayDurationManager.activeSessions 类型
data class FenceActiveSession(
    val fenceId: String,
    val fenceName: String,
    val enterTime: Date,
    val dateKey: String
)
