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
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.FenceStayRecord
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.trackmaster.app.FenceActiveSession
import com.trackmaster.app.throttle.ThrottleConfig
import com.trackmaster.app.di.appModule
import org.koin.android.ext.koin.androidLogger
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import com.trackmaster.app.ui.theme.ThemeManager

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

    private lateinit var throttleConfig: ThrottleConfig

    private var locationService: GeoFenceLocationService? = null
    private var serviceBound = false

    val geoFences = CopyOnWriteArrayList<GeoFence>()
    val categories = CopyOnWriteArrayList<Category>()
    val fenceEnterTimes = ConcurrentHashMap<String, Date>()
    val fenceStayRecords = CopyOnWriteArrayList<FenceStayRecord>()
    val geoFenceEvents = CopyOnWriteArrayList<GeoFenceEvent>()
    
    /** 当前所有命中的围栏列表（包括嵌套的父级和子级，按深度排序） */
    val currentMatchedFences = CopyOnWriteArrayList<GeoFence>()

    /** 围栏状态版本号，用于触发 UI 刷新 */
    private val _fenceStateVersion = MutableStateFlow(0)
    val fenceStateVersion: StateFlow<Int> = _fenceStateVersion

    /** 是否正在恢复首次定位状态（防止 checkGeoFences 覆盖恢复状态） */
    private var isRestoringFirstLocation = false

    /** 通知 UI 围栏状态已更新 */
    fun notifyFenceStateChanged() {
        _fenceStateVersion.value++
    }

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

        // 初始化主题管理器 - 必须最早执行以确保主题正确加载
        ThemeManager.init(this)
        Log.d(TAG, "主题初始化完成: ${ThemeManager.getCurrentTheme()}")

        // 初始化 Koin 依赖注入
        startKoin {
            androidLogger()
            androidContext(this@TrackMasterApplication)
            modules(appModule)
        }
        Log.d(TAG, "Koin 依赖注入初始化完成")

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
        throttleConfig = createThrottleConfigFromPreferences()
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

    fun checkGeoFences(location: AMapLocation): Boolean {
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

        // 同步评估是否需要处理（用于控制定位频率）- 不执行完整处理
        val shouldProcess = engine.evaluateLocation(fix)

        // 同步更新当前匹配的围栏列表（用于UI显示）
        // 这样定位服务可以立即读取到正确的围栏状态
        engine.setCurrentMatchedFenceIds(engine.checkFenceMatch(fix))

        // 异步处理围栏事件
        appScope.launch {
            val (closedSeg, _) = engine.onLocationUpdate(fix)

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
                // 清除该围栏的进入时间记录
                fenceEnterTimes.remove(closedSeg.leafGeofenceId)

                // ========== BUG FIX: 嵌套围栏计时问题 ==========
                // 问题：退出子围栏时，如果父围栏没有active session，需要启动它
                // 同时清理掉其他无关的session（如果有）
                val parentId = fenceTreeManager.getParent(closedSeg.leafGeofenceId)
                if (parentId != null && engine.currentMatchedFenceIds.contains(parentId)) {
                    // 清除所有session，只保留父围栏
                    val currentSessions = stayDurationManager.activeSessions
                    Log.d(TAG, "退出子围栏，清理多余session，当前activeSessions: ${currentSessions.keys.joinToString()}")
                    currentSessions.clear()

                    // 启动父围栏的计时
                    val parentFence = geoFences.find { it.id == parentId }
                    val parentName = parentFence?.name ?: parentId
                    Log.d(TAG, "启动父围栏计时: $parentName")
                    stayDurationManager.onFenceEnter(parentId, parentName, Date(location.time))
                    fenceEnterTimes[parentId] = Date(location.time)
                }
                // ========== BUG FIX END ==========

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
            // 收集刚退出的围栏ID，避免重复进入
            // （刚退出的围栏可能还在 allMatchedFenceIds 中，因为它是上一个位置的状态）
            val justExitedFenceIds = mutableSetOf<String>()
            if (closedSeg != null) {
                justExitedFenceIds.add(closedSeg.leafGeofenceId)
            }

            if (allMatchedFenceIds.isNotEmpty()) {
                Log.d(TAG, "当前所在围栏层级: ${allMatchedFenceIds.size}个 - ${allMatchedFenceIds.joinToString(", ")}")

                // 记录所有命中的围栏（用于UI显示）
                // 但如果正在恢复首次定位状态，则跳过（恢复逻辑会处理）
                if (!isRestoringFirstLocation) {
                    currentMatchedFences.clear()
                    for (fenceId in allMatchedFenceIds) {
                        val fence = geoFences.find { it.id == fenceId }
                        if (fence != null) {
                            currentMatchedFences.add(fence)
                        }
                    }
                    // 通知 UI 围栏状态已更新
                    notifyFenceStateChanged()
                } else {
                    Log.d(TAG, "跳过 currentMatchedFences 更新（正在恢复首次定位状态）")
                }

                // 处理每个围栏的进入事件（只有面积最小的围栏用于计时）
                val smallestFenceId = allMatchedFenceIds.firstOrNull()
                for (fenceId in allMatchedFenceIds) {
                    // 跳过刚退出的围栏，避免重复进入
                    if (fenceId in justExitedFenceIds) {
                        Log.d(TAG, "跳过刚退出的围栏重新进入: $fenceId")
                        continue
                    }

                    val fence = geoFences.find { it.id == fenceId }
                    val fenceName = fence?.name ?: fenceId

                    if (!fenceEnterTimes.containsKey(fenceId)) {
                        Log.d(TAG, "进入围栏: $fenceName")

                        recordFenceEvent(
                            fenceId = fenceId,
                            fenceName = fenceName,
                            action = GeoFenceAction.ENTER,
                            location = location
                        )

                        // 只有 smallest（最深的叶子围栏）启动计时
                        // 父围栏只记录进入时间，不启动计时
                        if (fenceId == smallestFenceId) {
                            // ========== BUG FIX: 清理多余的session ==========
                            // 进入最深围栏时，如果该围栏没有active session才启动新session
                            // 这样恢复的session不会被重置
                            if (!stayDurationManager.isFenceActive(fenceId)) {
                                Log.d(TAG, "进入最深围栏 $fenceName，启动计时")
                                stayDurationManager.onFenceEnter(fenceId, fenceName, Date(location.time))
                                persistentStateManager.saveCurrentFenceState(
                                    fenceId,
                                    fenceName,
                                    Date(location.time)
                                )
                            } else {
                                Log.d(TAG, "围栏 $fenceName 已在计时中，跳过")
                            }
                            // ========== BUG FIX END ==========
                        }

                        fenceEnterTimes[fenceId] = Date(location.time)
                    }
                }
            } else {
                // 如果正在恢复首次定位状态，则跳过（恢复逻辑会处理）
                if (!isRestoringFirstLocation) {
                    currentMatchedFences.clear()
                    // 通知 UI 围栏状态已更新
                    notifyFenceStateChanged()
                } else {
                    Log.d(TAG, "跳过 currentMatchedFences 清除（正在恢复首次定位状态）")
                }
            }
        }

        return shouldProcess
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
        Log.d(TAG, "刷新Engine: 核心围栏数=${coreFences.size}, geoFences总数=${geoFences.size}")
        engine = Engine(coreFences, repository, throttleConfig)
        // 设置位置处理统计回调
        engine.onLocationProcessed = { shouldProcess ->
            appPreferences.incrementLocations(shouldProcess)
        }
        aggregator = Aggregator(coreFences, repository)
        appScope.launch { engine.init() }
        // 通知 UI 围栏数据已更新
        notifyFenceStateChanged()
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
        // 防止重复调用：只有在不是正在恢复状态时才启动恢复
        if (isRestoringFirstLocation) {
            Log.d(TAG, "正在恢复中，跳过新的首次定位处理")
            return
        }

        // 启动协程处理恢复逻辑
        appScope.launch {
            isRestoringFirstLocation = true
            try {
                handleFirstLocationAfterRestoreInternal(location)
            } finally {
                isRestoringFirstLocation = false
            }
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

        Log.d(TAG, "首次定位处理: 位置=(${fix.lat}, ${fix.lng}), 围栏数=${geoFences.size}")

        val vacuumStartTs = lastAppCloseTime?.time
        val engineRestoreResult = engine.initWithLocation(fix, vacuumStartTs)

        Log.d(TAG, "Engine恢复结果: shouldRestore=${engineRestoreResult.shouldRestore}, fenceId=${engineRestoreResult.fenceId}, vacuum=${engineRestoreResult.vacuumDurationMs}ms")

        if (currentFenceState == null) {
            Log.d(TAG, "没有需要恢复的围栏状态")

            // 即使没有持久化状态，也要处理Engine的恢复结果
            if (engineRestoreResult.shouldRestore && engineRestoreResult.fenceId != null) {
                Log.d(TAG, "Engine检测到未关闭的Segment，恢复围栏: ${engineRestoreResult.fenceId}")
                // 更新Application状态以匹配Engine
                updateApplicationStateForFence(engineRestoreResult.fenceId, engineRestoreResult.enterTs, location)
                // 同步更新Engine的currentLeafId
                engine.setCurrentFenceForRecovery(engineRestoreResult.fenceId, engineRestoreResult.enterTs)
            } else {
                // 没有之前的状态也没有未关闭的Segment
                // 检查当前位置是否在任意围栏内（新导入围栏的场景）
                val currentMatchedFenceIds = engine.checkFenceMatch(fix)
                Log.d(TAG, "checkFenceMatch结果: ${currentMatchedFenceIds.size}个匹配, IDs=${currentMatchedFenceIds.joinToString()}")
                if (currentMatchedFenceIds.isNotEmpty()) {
                    val fenceId = currentMatchedFenceIds.first()
                    val fence = geoFences.find { it.id == fenceId }
                    val fenceName = fence?.name ?: fenceId
                    Log.d(TAG, "首次定位检测到用户在围栏 $fenceName 内，开始计时")
                    updateApplicationStateForFence(fenceId, fix.timestamp, location)
                    // 同步更新Engine的currentLeafId
                    engine.setCurrentFenceForRecovery(fenceId, fix.timestamp)
                } else {
                    Log.d(TAG, "首次定位：用户不在任何围栏内")
                }
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

        // 计算当前实际应该在的围栏（基于最新位置）
        // 注意：currentMatchedFenceIds 已经在 checkFenceMatch(fix) 中被更新
        val actualCurrentFenceId = currentMatchedFenceIds.firstOrNull()

        // 判断引擎恢复的围栏是否仍是用户当前所在的围栏
        // 只有当 engineFenceId 在 currentMatchedFenceIds 中时，引擎结果才是有效的
        val engineResultValid = engineFenceId != null &&
            engineRestoreResult.shouldRestore &&
            currentMatchedFenceIds.contains(engineFenceId)

        if (shouldUseEngineResult && engineResultValid) {
            // Engine检测到未关闭的Segment，且该围栏仍是用户当前所在的围栏
            Log.d(TAG, "优先使用Engine恢复结果")

            if (engineFenceId == currentFenceState.fenceId) {
                // Engine和Application状态一致，用户仍在围栏内
                Log.d(TAG, "状态一致: 用户仍在围栏 ${currentFenceState.fenceName} 内")
                updateApplicationStateForFence(currentFenceState.fenceId, currentFenceState.enterTime.time, location)
                engine.setCurrentFenceForRecovery(currentFenceState.fenceId, currentFenceState.enterTime.time)
            } else {
                // 状态不一致：Application说在围栏A，但用户实际在围栏B（或已离开）
                Log.d(TAG, "状态不一致: Application在${currentFenceState.fenceName}, 用户实际在${actualCurrentFenceId ?: "围栏外"}")

                // 结束Application的旧围栏计时（使用真空期开始时间或当前位置时间）
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

                // 用户实际在的围栏（engineFenceId 已在 currentMatchedFenceIds 中）
                updateApplicationStateForFence(engineFenceId!!, engineRestoreResult.enterTs, location)
                engine.setCurrentFenceForRecovery(engineFenceId, engineRestoreResult.enterTs)
            }
        } else {
            // Engine结果无效（用户已移动到其他围栏）或没有Engine恢复结果
            // 需要根据实际位置处理围栏转换

            // 如果用户仍在之前的围栏内（isStillInFence=true），但数据库没有记录，
            // 说明app被强制关闭而saveAllActiveSessions()未被调用
            // 此时应该恢复计时，而不是重新开始
            if (currentFenceState != null && isStillInFence) {
                Log.d(TAG, "用户仍在围栏 ${currentFenceState.fenceName} 内（数据库无记录），恢复计时")
                // 使用原始进入时间恢复计时，不使用重启时间
                updateApplicationStateForFence(currentFenceState.fenceId, currentFenceState.enterTime.time, location)
                engine.setCurrentFenceForRecovery(currentFenceState.fenceId, currentFenceState.enterTime.time)
                // 恢复完成后返回，不执行后续的计时开始逻辑
                return
            } else if (currentFenceState != null) {
                // 用户确实已离开围栏，结束计时
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
                Log.d(TAG, "已关闭旧围栏 ${currentFenceState.fenceName} 的计时")
            }

            // 根据Engine恢复结果和实际位置决定下一步
            if (shouldUseEngineResult && !engineResultValid) {
                // Engine有未关闭Segment，但用户已不在该围栏
                // 使用Engine的进入时间在当前围栏内继续计时
                if (actualCurrentFenceId != null && actualCurrentFenceId != engineFenceId) {
                    Log.d(TAG, "Engine在${engineFenceId}，用户实际在${actualCurrentFenceId}，使用实际围栏")
                    startFenceTiming(actualCurrentFenceId, location.time, location, currentMatchedFenceIds)
                    engine.setCurrentFenceForRecovery(actualCurrentFenceId, location.time)
                } else if (actualCurrentFenceId == null) {
                    // 用户不在任何围栏内
                    Log.d(TAG, "用户不在任何围栏内")
                    engine.setCurrentFenceForRecovery(null, location.time)
                }
            } else if (actualCurrentFenceId != null) {
                // 用户在围栏内，开始计时
                Log.d(TAG, "用户在围栏 $actualCurrentFenceId 内，开始新计时")
                startFenceTiming(actualCurrentFenceId, location.time, location, currentMatchedFenceIds)
                engine.setCurrentFenceForRecovery(actualCurrentFenceId, location.time)
            } else {
                // 用户不在任何围栏内
                Log.d(TAG, "用户不在任何围栏内")
                engine.setCurrentFenceForRecovery(null, location.time)
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
     * @param location 当前位置（未使用）
     */
    @Suppress("UNUSED_PARAMETER")
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

        // 通知 UI 围栏状态已更新
        notifyFenceStateChanged()

        Log.d(TAG, "Application状态已更新以匹配Engine")
    }

    /**
     * 开始围栏计时（处理进入事件）
     * 包含完整的进入处理流程：记录事件、启动计时、更新状态、通知UI
     *
     * @param fenceId 要进入的围栏ID
     * @param enterTs 进入时间戳（毫秒）
     * @param location 当前位置
     * @param matchedFenceIds 当前所有命中的围栏ID列表（按深度排序）
     */
    private fun startFenceTiming(fenceId: String, enterTs: Long, location: AMapLocation, matchedFenceIds: List<String>) {
        val fence = geoFences.find { it.id == fenceId }
        val fenceName = fence?.name ?: fenceId
        val enterTime = Date(enterTs)

        Log.d(TAG, "开始围栏计时: $fenceName, 进入时间=$enterTime")

        // 记录进入事件
        recordFenceEvent(
            fenceId = fenceId,
            fenceName = fenceName,
            action = GeoFenceAction.ENTER,
            location = location
        )

        // 启动围栏计时
        stayDurationManager.onFenceEnter(fenceId, fenceName, enterTime)
        fenceEnterTimes[fenceId] = enterTime

        // 保存当前围栏状态
        persistentStateManager.saveCurrentFenceState(fenceId, fenceName, enterTime)

        // 更新当前匹配的围栏列表（包括所有匹配的围栏）
        currentMatchedFences.clear()
        for (id in matchedFenceIds) {
            val f = geoFences.find { it.id == id }
            if (f != null) {
                currentMatchedFences.add(f)
            }
        }

        // 通知 UI 围栏状态已更新
        notifyFenceStateChanged()
    }

    /**
     * 检查当前位置是否在围栏内并更新状态（导入围栏后调用）
     */
    fun checkAndUpdateFenceState() {
        val lastLoc = persistentStateManager.getLastLocation()
        if (lastLoc == null) {
            Log.d(TAG, "checkAndUpdateFenceState: 无上次位置，跳过")
            return
        }

        val fix = LocationFix(
            lat = lastLoc.latitude,
            lng = lastLoc.longitude,
            accuracy = 50f, // 使用估算精度
            timestamp = lastLoc.time.time
        )

        val matchedIds = engine.checkFenceMatch(fix)
        Log.d(TAG, "checkAndUpdateFenceState: 位置=(${fix.lat}, ${fix.lng}), 匹配=${matchedIds.size}个")

        if (matchedIds.isNotEmpty()) {
            val fenceId = matchedIds.first()
            val fence = geoFences.find { it.id == fenceId }
            val fenceName = fence?.name ?: fenceId
            val enterTs = System.currentTimeMillis()
            val enterTime = Date(enterTs)

            Log.d(TAG, "checkAndUpdateFenceState: 检测到在围栏 $fenceName 内")

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

            // 通知 UI 围栏状态已更新
            notifyFenceStateChanged()

            // 同步Engine状态
            engine.setCurrentFenceForRecovery(fenceId, enterTs)

            Log.d(TAG, "checkAndUpdateFenceState: 状态已更新")
        }
    }

    /** 导出围栏列表到JSON字符串 */
    fun exportFencesToJson(fences: List<GeoFence>): String? {
        return dbHelper.generateFencesJson(fences)
    }

    /** 导入围栏并刷新所有相关状态 */
    fun importFencesAndRefresh(jsonContent: String): Int {
        val count = dbHelper.importFencesFromJson(jsonContent)
        if (count >= 0) {
            // 刷新Application的围栏数据
            geoFences.clear()
            geoFences.addAll(dbHelper.getAllFences())
            // 刷新Engine的围栏数据
            refreshEngineFences()
            // 检查当前位置是否在围栏内
            checkAndUpdateFenceState()
        }
        return count
    }

}
