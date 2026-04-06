package com.trackmaster.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.amap.api.location.AMapLocation
import com.trackmaster.app.core.Engine
import com.trackmaster.app.core.FenceMapper
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.core.LocationFix
import com.trackmaster.app.services.Aggregator
import com.trackmaster.app.storage.Repository
import com.trackmaster.app.storage.SqliteStorageAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.trackmaster.app.FenceActiveSession
import com.trackmaster.app.throttle.ThrottleConfig

class TrackMasterApplication : Application() {

    companion object {
        private const val TAG = "TrackMasterApplication"
        lateinit var instance: TrackMasterApplication
            private set
    }

    lateinit var dbHelper: GeoFenceDatabaseHelper
        private set

    lateinit var categoryManager: CategoryManager
        private set

    lateinit var statisticsManager: StatisticsManager
        private set

    lateinit var dataManager: DataManager
        private set

    lateinit var stayDurationManager: StayDurationManager
        private set

    lateinit var engine: Engine
        private set

    lateinit var repository: Repository
        private set

    lateinit var aggregator: Aggregator
        private set

    lateinit var fenceTreeManager: FenceTreeManager
        private set

    lateinit var persistentStateManager: PersistentStateManager
        private set
        
    lateinit var appPreferences: AppPreferences
        private set

    private var locationService: GeoFenceLocationService? = null
    private var serviceBound = false

    val geoFences = CopyOnWriteArrayList<GeoFence>()
    val categories = CopyOnWriteArrayList<Category>()
    val fenceEnterTimes = ConcurrentHashMap<String, Date>()
    val fenceStayRecords = CopyOnWriteArrayList<FenceStayRecord>()
    val geoFenceEvents = CopyOnWriteArrayList<GeoFenceEvent>()
    
    /** 当前所有命中的围栏列表（包括嵌套的父级和子级，按深度排序） */
    val currentMatchedFences = CopyOnWriteArrayList<GeoFence>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "定位服务已连接")
            val binder = service as? GeoFenceLocationService.LocalBinder
            locationService = binder?.service
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "定位服务已断开")
            locationService = null
            serviceBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        dbHelper = GeoFenceDatabaseHelper(this)
        categoryManager = CategoryManager(this)
        statisticsManager = StatisticsManager(this)
        dataManager = DataManager(this)
        stayDurationManager = StayDurationManager(this)
        persistentStateManager = PersistentStateManager(this)
        appPreferences = AppPreferences(this)
        Log.d(TAG, "应用创建，初始化数据库")

        loadDataFromDatabase()

        // 初始化围栏树管理器和核心引擎
        fenceTreeManager = FenceTreeManager(this)
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        val storageAdapter = SqliteStorageAdapter(dbHelper)
        repository = Repository(storageAdapter)
        
        // 从 AppPreferences 创建配置
        val throttleConfig = createThrottleConfigFromPreferences()
        engine = Engine(coreFences, repository, throttleConfig)
        // 设置位置处理统计回调
        engine.onLocationProcessed = { shouldProcess ->
            appPreferences.incrementLocations(shouldProcess)
        }
        aggregator = Aggregator(coreFences, repository)
        Log.d(TAG, "引擎初始化完成: ${coreFences.size} 个核心围栏")

        // 恢复持久化状态
        restorePersistentState()
    }

    private fun loadDataFromDatabase() {
        try {
            categories.clear()
            categories.addAll(dbHelper.getAllCategories())
            Log.d(TAG, "从数据库加载了 ${categories.size} 个分类")

            geoFences.clear()
            geoFences.addAll(dbHelper.getAllFences())
            Log.d(TAG, "从数据库加载了 ${geoFences.size} 个围栏")

            geoFenceEvents.clear()
            geoFenceEvents.addAll(dbHelper.getEventsByTimeRange(Date(0), Date()))
            Log.d(TAG, "从数据库加载了 ${geoFenceEvents.size} 个事件")

            fenceStayRecords.clear()
            fenceStayRecords.addAll(dbHelper.getAllStayRecords())
            Log.d(TAG, "从数据库加载了 ${fenceStayRecords.size} 条停留记录")
        } catch (e: Exception) {
            Log.e(TAG, "加载数据失败", e)
        }
    }

    fun startLocationService() {
        if (!serviceBound) {
            val intent = Intent(this, GeoFenceLocationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "启动定位服务")
        }
    }

    fun stopLocationService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        val intent = Intent(this, GeoFenceLocationService::class.java)
        stopService(intent)
        Log.d(TAG, "停止定位服务")
    }

    fun getLocationService(): GeoFenceLocationService? = locationService

    private val appScope = CoroutineScope(Dispatchers.IO)

    fun checkGeoFences(location: AMapLocation) {
        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time,
        )

        // 保存当前位置
        persistentStateManager.saveLastLocation(
            location.latitude,
            location.longitude,
            Date(location.time)
        )

        appScope.launch {
            val closedSeg = engine.onLocationUpdate(fix)

            // 处理离开的围栏（如果有）
            if (closedSeg != null) {
                val fenceName = geoFences.find { it.id == closedSeg.leafGeofenceId }?.name ?: closedSeg.leafGeofenceId
                Log.d(TAG, "离开围栏: $fenceName")

                // 记录退出事件
                recordFenceEvent(
                    fenceId = closedSeg.leafGeofenceId,
                    fenceName = fenceName,
                    action = GeoFenceAction.EXIT,
                    location = location
                )

                // 停止该围栏的计时
                stayDurationManager.onFenceExit(closedSeg.leafGeofenceId, fenceName, Date(location.time))
                fenceEnterTimes.remove(closedSeg.leafGeofenceId)

                // 保存上次离开状态
                persistentStateManager.saveLastExitState(
                    closedSeg.leafGeofenceId,
                    fenceName,
                    Date(location.time)
                )

                // 清除当前围栏状态
                persistentStateManager.clearCurrentFenceState()
            }

            // 处理所有当前命中的围栏（支持嵌套围栏）
            val allMatchedFenceIds = engine.currentMatchedFenceIds
            if (allMatchedFenceIds.isNotEmpty()) {
                Log.d(TAG, "当前所在围栏层级: ${allMatchedFenceIds.size}个 - ${allMatchedFenceIds.joinToString(", ")}")

                // 记录所有命中的围栏（用于UI显示）
                currentMatchedFences.clear()
                for (fenceId in allMatchedFenceIds) {
                    val fence = geoFences.find { it.id == fenceId }
                    if (fence != null) {
                        currentMatchedFences.add(fence)
                    }
                }

                // 处理每个围栏的进入事件（只有面积最小的围栏用于计时）
                val smallestFenceId = allMatchedFenceIds.firstOrNull() // 已按面积排序，第一个是最小的
                for (fenceId in allMatchedFenceIds) {
                    val fence = geoFences.find { it.id == fenceId }
                    val fenceName = fence?.name ?: fenceId

                    // 检查是否已经在该围栏中（避免重复记录）
                    if (!fenceEnterTimes.containsKey(fenceId)) {
                        Log.d(TAG, "进入围栏: $fenceName")

                        // 记录进入事件
                        recordFenceEvent(
                            fenceId = fenceId,
                            fenceName = fenceName,
                            action = GeoFenceAction.ENTER,
                            location = location
                        )

                        // 只有面积最小的围栏开始计时
                        if (fenceId == smallestFenceId) {
                            stayDurationManager.onFenceEnter(fenceId, fenceName, Date(location.time))

                            // 保存当前围栏状态
                            persistentStateManager.saveCurrentFenceState(
                                fenceId,
                                fenceName,
                                Date(location.time)
                            )
                        }

                        fenceEnterTimes[fenceId] = Date(location.time)
                    }
                }
            } else {
                // 清空当前命中的围栏列表
                currentMatchedFences.clear()
            }
        }
    }

    /**
     * 记录围栏事件到数据库
     */
    private fun recordFenceEvent(
        fenceId: String,
        fenceName: String,
        action: GeoFenceAction,
        location: AMapLocation
    ) {
        val event = GeoFenceEvent(
            fenceId = fenceId,
            fenceName = fenceName,
            action = action,
            time = Date(location.time),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy
        )
        
        // 添加到内存列表
        geoFenceEvents.add(event)
        
        // 保存到数据库
        dbHelper.insertEvent(event)
        
        Log.d(TAG, "记录围栏事件: ${action.name} - $fenceName")
    }

    /** 刷新引擎围栏数据（围栏增删后调用） */
    fun refreshEngineFences() {
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        engine = Engine(coreFences, repository)
        // 设置位置处理统计回调
        engine.onLocationProcessed = { shouldProcess ->
            appPreferences.incrementLocations(shouldProcess)
        }
        aggregator = Aggregator(coreFences, repository)
        appScope.launch { engine.init() }
    }

    /**
     * 恢复持久化状态
     *
     * 应用启动时调用，处理以下场景：
     * 1. 用户还在上次所在的围栏内 - 继续累计
     * 2. 用户移动到另一个围栏 - 切换累计
     * 3. 用户不在任何围栏内 - 记录离开时间
     */
    private fun restorePersistentState() {
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime()
        val currentFenceState = persistentStateManager.getCurrentFenceState()
        val lastExitState = persistentStateManager.getLastExitState()
        val lastLocation = persistentStateManager.getLastLocation()

        Log.d(TAG, "恢复持久化状态:")
        Log.d(TAG, "  上次应用关闭时间: $lastAppCloseTime")
        Log.d(TAG, "  当前围栏状态: $currentFenceState")
        Log.d(TAG, "  上次离开状态: $lastExitState")
        Log.d(TAG, "  上次位置: $lastLocation")

        // 如果没有持久化状态，直接返回
        if (currentFenceState == null && lastExitState == null) {
            Log.d(TAG, "没有持久化状态需要恢复")
            return
        }

        // 恢复活动会话
        stayDurationManager.loadActiveSessionsFromDatabase()

        // 如果有当前围栏状态，需要等待首次定位后处理
        if (currentFenceState != null) {
            Log.d(TAG, "等待首次定位以恢复围栏状态: ${currentFenceState.fenceName}")
        }
    }

    /**
     * 处理首次定位后的状态恢复
     *
     * @param location 首次定位结果
     */
    fun handleFirstLocationAfterRestore(location: AMapLocation) {
        // 启动协程处理恢复逻辑
        appScope.launch {
            handleFirstLocationAfterRestoreInternal(location)
        }
    }

    /**
     * 内部恢复逻辑（协程版本）
     */
    private suspend fun handleFirstLocationAfterRestoreInternal(location: AMapLocation) {
        val currentFenceState = persistentStateManager.getCurrentFenceState()
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime()

        // 首先使用Engine验证数据库中的Segment
        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time,
        )

        val vacuumStartTs = lastAppCloseTime?.time
        val engineRestoreResult = engine.initWithLocation(fix, vacuumStartTs)

        Log.d(TAG, "Engine恢复结果: shouldRestore=${engineRestoreResult.shouldRestore}, fenceId=${engineRestoreResult.fenceId}, vacuum=${engineRestoreResult.vacuumDurationMs}ms")

        if (currentFenceState == null) {
            Log.d(TAG, "没有需要恢复的围栏状态")

            // 即使没有持久化状态，也要处理Engine的恢复结果
            if (engineRestoreResult.shouldRestore && engineRestoreResult.fenceId != null) {
                Log.d(TAG, "Engine检测到未关闭的Segment，恢复围栏: ${engineRestoreResult.fenceId}")
                // 更新Application状态以匹配Engine
                updateApplicationStateForFence(engineRestoreResult.fenceId!!, engineRestoreResult.enterTs, location)
            }
            return
        }

        // 检查当前位置是否在持久化的围栏内
        val currentMatchedFenceIds = engine.checkFenceMatch(fix)
        val isStillInFence = currentMatchedFenceIds.contains(currentFenceState.fenceId)

        Log.d(TAG, "状态恢复检查:")
        Log.d(TAG, "  持久化围栏: ${currentFenceState.fenceName}")
        Log.d(TAG, "  当前匹配围栏: ${currentMatchedFenceIds.joinToString(", ")}")
        Log.d(TAG, "  Engine恢复结果: shouldRestore=${engineRestoreResult.shouldRestore}, fenceId=${engineRestoreResult.fenceId}")
        Log.d(TAG, "  是否仍在围栏内: $isStillInFence")

        // 检查Engine恢复结果与Application持久化状态是否一致
        val engineFenceId = engineRestoreResult.fenceId
        val shouldUseEngineResult = engineFenceId != null && engineRestoreResult.shouldRestore

        if (shouldUseEngineResult) {
            // Engine检测到未关闭的Segment，优先使用Engine的结果
            Log.d(TAG, "优先使用Engine恢复结果")

            if (engineFenceId == currentFenceState.fenceId) {
                // Engine和Application状态一致，用户仍在围栏内
                Log.d(TAG, "状态一致: 用户仍在围栏 ${currentFenceState.fenceName} 内")
                updateApplicationStateForFence(currentFenceState.fenceId, currentFenceState.enterTime.time, location)
            } else {
                // 状态不一致：Application说在围栏A，Engine说在围栏B（或已离开）
                Log.d(TAG, "状态不一致: Application在${currentFenceState.fenceName}, Engine在${engineFenceId ?: "已离开"}")

                // 结束Application的围栏计时（使用真空期开始时间或当前位置时间）
                val exitTime = lastAppCloseTime ?: Date(location.time)
                stayDurationManager.onFenceExit(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
                fenceEnterTimes.remove(currentFenceState.fenceId)

                // 保存上次离开状态
                persistentStateManager.saveLastExitState(
                    currentFenceState.fenceId,
                    currentFenceState.fenceName,
                    exitTime
                )

                // 清除当前围栏状态
                persistentStateManager.clearCurrentFenceState()

                // 如果Engine检测到用户在另一个围栏内，开始新的计时
                if (engineRestoreResult.shouldRestore && engineFenceId != null) {
                    updateApplicationStateForFence(engineFenceId, engineRestoreResult.enterTs, location)
                } else if (currentMatchedFenceIds.isNotEmpty()) {
                    // Engine说已离开，但当前位置在另一个围栏内
                    val smallestFenceId = currentMatchedFenceIds.firstOrNull()
                    if (smallestFenceId != null) {
                        val fence = geoFences.find { it.id == smallestFenceId }
                        val fenceName = fence?.name ?: smallestFenceId

                        Log.d(TAG, "用户现在在围栏 $fenceName 内，开始新计时")

                        // 记录进入事件
                        recordFenceEvent(
                            fenceId = smallestFenceId,
                            fenceName = fenceName,
                            action = GeoFenceAction.ENTER,
                            location = location
                        )

                        // 开始计时
                        stayDurationManager.onFenceEnter(smallestFenceId, fenceName, Date(location.time))
                        fenceEnterTimes[smallestFenceId] = Date(location.time)

                        // 保存当前围栏状态
                        persistentStateManager.saveCurrentFenceState(
                            smallestFenceId,
                            fenceName,
                            Date(location.time)
                        )

                        // 更新当前匹配的围栏列表
                        currentMatchedFences.clear()
                        for (fenceId in currentMatchedFenceIds) {
                            val foundFence = geoFences.find { it.id == fenceId }
                            if (foundFence != null) {
                                currentMatchedFences.add(foundFence)
                            }
                        }
                    }
                }
            }
        } else {
            // Engine没有检测到未关闭的Segment，使用Application的持久化状态
            if (isStillInFence) {
                // 用户仍在围栏内
                Log.d(TAG, "用户仍在围栏 ${currentFenceState.fenceName} 内，继续累计")
                updateApplicationStateForFence(currentFenceState.fenceId, currentFenceState.enterTime.time, location)
            } else {
                // 用户已离开围栏
                Log.d(TAG, "用户不在围栏 ${currentFenceState.fenceName} 内")

                // 结束围栏计时
                val exitTime = lastAppCloseTime ?: Date(location.time)
                stayDurationManager.onFenceExit(currentFenceState.fenceId, currentFenceState.fenceName, exitTime)
                fenceEnterTimes.remove(currentFenceState.fenceId)

                // 保存上次离开状态
                persistentStateManager.saveLastExitState(
                    currentFenceState.fenceId,
                    currentFenceState.fenceName,
                    exitTime
                )

                // 清除当前围栏状态
                persistentStateManager.clearCurrentFenceState()

                // 如果用户现在在另一个围栏内，开始新的计时
                if (currentMatchedFenceIds.isNotEmpty()) {
                    val smallestFenceId = currentMatchedFenceIds.firstOrNull()
                    if (smallestFenceId != null) {
                        val fence = geoFences.find { it.id == smallestFenceId }
                        val fenceName = fence?.name ?: smallestFenceId

                        Log.d(TAG, "用户现在在围栏 $fenceName 内，开始新计时")

                        // 记录进入事件
                        recordFenceEvent(
                            fenceId = smallestFenceId,
                            fenceName = fenceName,
                            action = GeoFenceAction.ENTER,
                            location = location
                        )

                        // 开始计时
                        stayDurationManager.onFenceEnter(smallestFenceId, fenceName, Date(location.time))
                        fenceEnterTimes[smallestFenceId] = Date(location.time)

                        // 保存当前围栏状态
                        persistentStateManager.saveCurrentFenceState(
                            smallestFenceId,
                            fenceName,
                            Date(location.time)
                        )

                        // 更新当前匹配的围栏列表
                        currentMatchedFences.clear()
                        for (fenceId in currentMatchedFenceIds) {
                            val foundFence = geoFences.find { it.id == fenceId }
                            if (foundFence != null) {
                                currentMatchedFences.add(foundFence)
                            }
                        }
                    }
                }
            }
        }

        // 记录真空期信息
        if (lastAppCloseTime != null) {
            val vacuumDuration = (location.time - lastAppCloseTime.time) / 1000
            Log.d(TAG, "真空期时长: ${vacuumDuration}秒 (不计算)")
        }
    }

    /**
     * 保存应用关闭时的状态
     */
    fun saveStateOnAppClose() {
        val currentTime = Date()
        persistentStateManager.saveLastAppCloseTime(currentTime)

        // 保存当前活动会话
        stayDurationManager.saveAllActiveSessions()

        Log.d(TAG, "保存应用关闭状态: $currentTime")
    }

    /**
     * 获取真空期信息
     *
     * @return 真空期时长（秒），如果没有真空期返回 null
     */
    fun getVacuumDuration(): Long? {
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime() ?: return null
        val currentTime = System.currentTimeMillis()
        val vacuumDuration = (currentTime - lastAppCloseTime.time) / 1000

        return if (vacuumDuration > 0) vacuumDuration else null
    }

    /**
     * 获取上次离开的围栏信息
     *
     * @return 上次离开的围栏状态，如果没有返回 null
     */
    fun getLastExitFence(): PersistentStateManager.LastExitState? {
        return persistentStateManager.getLastExitState()
    }
    
    /**
     * 从 AppPreferences 创建 ThrottleConfig
     */
    private fun createThrottleConfigFromPreferences(): ThrottleConfig {
        return ThrottleConfig(
            driftThresholdM = appPreferences.driftThreshold,
            stationaryMinDistanceM = appPreferences.stationaryThreshold,
            boundaryBufferM = appPreferences.boundaryBuffer
        )
    }
    
    /**
     * 更新引擎配置（当用户在设置中修改参数时调用）
     */
    fun updateEngineConfig() {
        val newConfig = createThrottleConfigFromPreferences()
        engine.getScheduler().updateConfig(newConfig)
        Log.d(TAG, "引擎配置已更新")
    }

    /**
     * 更新Application状态以匹配Engine的恢复结果
     *
     * @param fenceId 围栏ID
     * @param enterTs 进入时间戳
     * @param location 当前位置
     */
    private fun updateApplicationStateForFence(fenceId: String, enterTs: Long, location: AMapLocation) {
        val fence = geoFences.find { it.id == fenceId }
        val fenceName = fence?.name ?: fenceId
        val enterTime = Date(enterTs)

        Log.d(TAG, "更新Application状态: 围栏=$fenceName, 进入时间=$enterTime")

        // 恢复进入时间
        fenceEnterTimes[fenceId] = enterTime

        // 恢复活动会话
        val session = FenceActiveSession(
            fenceId = fenceId,
            fenceName = fenceName,
            enterTime = enterTime,
            dateKey = StayDurationManager.formatDate(enterTime)
        )
        stayDurationManager.activeSessions[fenceId] = session

        // 保存当前围栏状态
        persistentStateManager.saveCurrentFenceState(fenceId, fenceName, enterTime)

        // 更新当前匹配的围栏列表
        currentMatchedFences.clear()
        val fenceObj = geoFences.find { it.id == fenceId }
        if (fenceObj != null) {
            currentMatchedFences.add(fenceObj)
        }

        Log.d(TAG, "Application状态已更新以匹配Engine")
    }

}
