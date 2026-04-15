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
    private var previousSmallestFenceId: String? = null

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
            previousSmallestFenceId = smallestFenceId
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
        if (previousSmallestFenceId == null || previousSmallestFenceId == smallestFenceId ||
            previousSmallestFenceId in allMatchedFenceIds) return

        val oldFenceName = geoFences.find { it.id == previousSmallestFenceId }?.name ?: previousSmallestFenceId ?: ""
        if (oldFenceName.isEmpty() || !stayDurationManager.isFenceActive(previousSmallestFenceId!!)) return

        Log.d(TAG, "强制退出旧围栏(围栏转换检测): $oldFenceName")
        stayDurationManager.onFenceExit(previousSmallestFenceId!!, oldFenceName, Date(location.timestamp))
        fenceEnterTimes.remove(previousSmallestFenceId!!)
        val oldParentId = fenceTreeManager.getParent(previousSmallestFenceId!!)
        if (oldParentId != null && allMatchedFenceIds.contains(oldParentId)) {
            stayDurationManager.transferChildDurationToParent(previousSmallestFenceId!!, oldParentId, Date(location.timestamp))
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

        if (currentFenceState == null) {
            Log.d(TAG, "没有需要恢复的围栏状态")
            if (engineResult.shouldRestore && engineResult.fenceId != null) {
                Log.d(TAG, "Engine检测到未关闭的Segment，恢复围栏: ${engineResult.fenceId}")
                updateApplicationStateForFence(engineResult.fenceId, engineResult.enterTs, location)
                engine.setCurrentFenceForRecovery(engineResult.fenceId, engineResult.enterTs)
            } else {
                val matchedIds = engine.checkFenceMatch(fix)
                if (matchedIds.isNotEmpty()) {
                    val fenceId = matchedIds.first()
                    val fence = geoFences.find { it.id == fenceId }
                    val fenceName = fence?.name ?: fenceId
                    Log.d(TAG, "首次定位检测到用户在围栏 $fenceName 内，开始计时")
                    updateApplicationStateForFence(fenceId, fix.timestamp, location)
                    engine.setCurrentFenceForRecovery(fenceId, fix.timestamp)
                }
            }
            return
        }

        val matchedIds = engine.checkFenceMatch(fix)
        val isStillInFence = matchedIds.contains(currentFenceState.fenceId)
        val engineFenceId = engineResult.fenceId
        val engineResultValid = engineFenceId != null && engineResult.shouldRestore && matchedIds.contains(engineFenceId)

        when {
            engineResultValid && engineFenceId == currentFenceState.fenceId -> {
                Log.d(TAG, "状态一致: 用户仍在围栏 ${currentFenceState.fenceName} 内")
                updateApplicationStateForFence(currentFenceState.fenceId, currentFenceState.enterTime.time, location)
                engine.setCurrentFenceForRecovery(currentFenceState.fenceId, currentFenceState.enterTime.time)
            }
            engineResultValid && engineFenceId != currentFenceState.fenceId -> {
                Log.d(TAG, "状态不一致: Application在${currentFenceState.fenceName}, 用户实际在${matchedIds.firstOrNull() ?: "围栏外"}")
                val exitTime = lastAppCloseTime ?: Date(location.timestamp)
                stayDurationManager.onFenceExit(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
                fenceEnterTimes.remove(currentFenceState.fenceId)
                persistentStateManager.saveLastExitState(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
                persistentStateManager.clearCurrentFenceState()
                updateApplicationStateForFence(engineFenceId!!, engineResult.enterTs, location)
                engine.setCurrentFenceForRecovery(engineFenceId, engineResult.enterTs)
            }
            currentFenceState != null && isStillInFence -> {
                Log.d(TAG, "用户仍在围栏 ${currentFenceState.fenceName} 内（数据库无记录），恢复计时")
                updateApplicationStateForFence(currentFenceState.fenceId, currentFenceState.enterTime.time, location)
                engine.setCurrentFenceForRecovery(currentFenceState.fenceId, currentFenceState.enterTime.time)
            }
            currentFenceState != null -> {
                val exitTime = lastAppCloseTime ?: Date(location.timestamp)
                stayDurationManager.onFenceExit(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
                fenceEnterTimes.remove(currentFenceState.fenceId)
                persistentStateManager.saveLastExitState(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
                persistentStateManager.clearCurrentFenceState()
                Log.d(TAG, "已关闭旧围栏 ${currentFenceState.fenceName} 的计时")
                startFenceTimingFromRestore(matchedIds.firstOrNull(), location.timestamp, location, matchedIds)
            }
            matchedIds.isNotEmpty() -> {
                Log.d(TAG, "用户在围栏 ${matchedIds.first()} 内，开始新计时")
                startFenceTimingFromRestore(matchedIds.first(), location.timestamp, location, matchedIds)
            }
            else -> {
                engine.setCurrentFenceForRecovery(null, location.timestamp)
            }
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
