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
        Log.d(TAG, "应用创建，初始化数据库")

        loadDataFromDatabase()

        // 初始化围栏树管理器和核心引擎
        fenceTreeManager = FenceTreeManager(this)
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        val storageAdapter = SqliteStorageAdapter(dbHelper)
        repository = Repository(storageAdapter)
        engine = Engine(coreFences, repository)
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
        val currentFenceState = persistentStateManager.getCurrentFenceState()
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime()

        if (currentFenceState == null) {
            Log.d(TAG, "没有需要恢复的围栏状态")
            return
        }

        // 检查当前位置是否在持久化的围栏内
        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time,
        )

        val currentMatchedFenceIds = engine.checkFenceMatch(fix)
        val isStillInFence = currentMatchedFenceIds.contains(currentFenceState.fenceId)

        Log.d(TAG, "首次定位恢复检查:")
        Log.d(TAG, "  持久化围栏: ${currentFenceState.fenceName}")
        Log.d(TAG, "  当前匹配围栏: ${currentMatchedFenceIds.joinToString(", ")}")
        Log.d(TAG, "  是否仍在围栏内: $isStillInFence")

        if (isStillInFence) {
            // 用户还在同一个围栏内，继续累计
            Log.d(TAG, "用户仍在围栏 ${currentFenceState.fenceName} 内，继续累计")

            // 恢复进入时间
            fenceEnterTimes[currentFenceState.fenceId] = currentFenceState.enterTime

            // 恢复活动会话
            val session = FenceActiveSession(
                fenceId = currentFenceState.fenceId,
                fenceName = currentFenceState.fenceName,
                enterTime = currentFenceState.enterTime,
                dateKey = StayDurationManager.formatDate(currentFenceState.enterTime)
            )
            stayDurationManager.activeSessions[currentFenceState.fenceId] = session

            // 更新当前匹配的围栏列表
            currentMatchedFences.clear()
            for (fenceId in currentMatchedFenceIds) {
                val fence = geoFences.find { it.id == fenceId }
                if (fence != null) {
                    currentMatchedFences.add(fence)
                }
            }

            // 记录恢复事件（不计算真空期）
            if (lastAppCloseTime != null) {
                val vacuumDuration = (location.time - lastAppCloseTime.time) / 1000
                Log.d(TAG, "真空期时长: ${vacuumDuration}秒 (不计算)")
            }
        } else {
            // 用户不在原来的围栏内
            Log.d(TAG, "用户不在围栏 ${currentFenceState.fenceName} 内")

            // 结束之前的围栏计时（不计算真空期）
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

}
