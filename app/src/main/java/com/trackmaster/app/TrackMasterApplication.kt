package com.trackmaster.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.core.EngineHolder
import com.trackmaster.app.core.FenceProcessingHolder
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.core.ManagersHolder
import com.trackmaster.app.core.RepositoryHolder
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.di.appModule
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import java.util.Date

/**
 * TrackMasterApplication — 应用生命周期容器
 *
 * 职责：
 * 1. Koin 依赖注入初始化
 * 2. ManagersHolder / FenceProcessingHolder 生命周期管理
 * 3. 定位服务绑定
 */
class TrackMasterApplication : Application() {

    companion object {
        private const val TAG = "TrackMasterApplication"
        lateinit var instance: TrackMasterApplication
            private set
    }

    // ============================================================
    // 数据库
    // ============================================================

    val dbHelper: com.trackmaster.app.data.database.DatabaseFacade by lazy { getKoin().get<com.trackmaster.app.data.database.DatabaseFacade>() }

    // ============================================================
    // Holder 层
    // ============================================================

    private val managersHolder = ManagersHolder()
    val categoryManager get() = managersHolder.categoryManager
    val statisticsManager get() = managersHolder.statisticsManager
    val dataManager get() = managersHolder.dataManager
    val stayDurationManager get() = managersHolder.stayDurationManager
    val persistentStateManager get() = managersHolder.persistentStateManager
    val appPreferences get() = managersHolder.appPreferences

    private val repositoryHolder: RepositoryHolder by lazy { getKoin().get<RepositoryHolder>() }
    private val engineHolder: EngineHolder by lazy { getKoin().get<EngineHolder>() }
    val fenceTreeManager: FenceTreeManager by lazy { getKoin().get<FenceTreeManager>() }

    val repository by lazy { repositoryHolder.repository }
    val engine by lazy { engineHolder.engine }

    // ============================================================
    // 数据代理（委托给 RepositoryHolder）
    // ============================================================

    val geoFences get() = repositoryHolder.geoFences
    val categories get() = repositoryHolder.categories
    val fenceEnterTimes get() = repositoryHolder.fenceEnterTimes
    val geoFenceEvents get() = repositoryHolder.geoFenceEvents
    val currentMatchedFences get() = repositoryHolder.currentMatchedFences
    val fenceStateVersion get() = repositoryHolder.fenceStateVersion
    val highlightedFenceId get() = repositoryHolder.highlightedFenceId
    val displayedFenceId get() = repositoryHolder.displayedFenceId

    fun notifyFenceStateChanged() = repositoryHolder.notifyFenceStateChanged()
    fun setHighlightedFenceId(fenceId: String?) = repositoryHolder.setHighlightedFenceId(fenceId)
    fun setDisplayedFenceId(fenceId: String?) = repositoryHolder.setDisplayedFenceId(fenceId)

    // ============================================================
    // FenceProcessingHolder（围栏处理核心逻辑）
    // ============================================================

    private lateinit var fenceProcessingHolder: FenceProcessingHolder

    /**
     * 应用级协程作用域：所有围栏状态变更都在这里串行执行。
     *
     * 必须带 SupervisorJob：否则任何一个子协程抛出未捕获异常（例如
     * Repository.flush 重试上限耗尽）都会连带取消整个 scope，
     * 之后每一次定位处理都会静默失效，且没有任何日志提示。
     * 配一个 CoroutineExceptionHandler 兜底记录，避免异常被彻底吞掉。
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            AppLog.e(TAG, "appScope 协程未捕获异常", throwable)
        }
    )

    // ============================================================
    // 定位服务
    // ============================================================

    private var locationService: GeoFenceLocationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            AppLog.d(TAG, "定位服务已连接")
            locationService = (service as? GeoFenceLocationService.LocalBinder)?.service
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            AppLog.d(TAG, "定位服务已断开")
            locationService = null
            serviceBound = false
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 主题设置由 ThemeStore 在首次组合时从 SharedPreferences 读取，
        // 不需要在这里初始化（见 ui/design/Theme.kt）。

        startKoin {
            androidLogger()
            androidContext(this@TrackMasterApplication)
            modules(appModule)
        }
        AppLog.d(TAG, "Koin 依赖注入初始化完成")

        managersHolder.init(this)
        AppLog.d(TAG, "应用创建，初始化数据库")

        loadDataFromDatabase()

        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        engineHolder.initialize(coreFences)
        AppLog.d(TAG, "引擎初始化完成: ${coreFences.size} 个核心围栏")

        fenceProcessingHolder = FenceProcessingHolder(
            engineHolder = engineHolder,
            repositoryHolder = repositoryHolder,
            managersHolder = managersHolder,
            fenceTreeManager = fenceTreeManager,
            dbHelper = dbHelper,
            appScope = appScope,
            onNotifyFenceStateChanged = { repositoryHolder.notifyFenceStateChanged() }
        )

        stayDurationManager.cleanupOrphanedParentSessions()
        restorePersistentState()
    }

    /**
     * 从数据库加载启动必需的数据。
     *
     * 只加载 Engine 初始化直接依赖的围栏与分类。事件列表由 UI 层按需加载
     * （HistoryScreen / HomeViewModel 各自查询），不在这里全量预载：
     * onCreate 跑在主线程，全表读取会直接转化为启动 ANR。
     */
    private fun loadDataFromDatabase() {
        try {
            categories.clear()
            categories.addAll(dbHelper.getAllCategories())
            geoFences.clear()
            geoFences.addAll(dbHelper.getAllFences())
        } catch (e: Exception) {
            AppLog.e(TAG, "加载数据失败", e)
        }
    }

    fun startLocationService() {
        // 用户已关闭追踪时不要启动。这条判断同时挡住了开机广播、
        // WakeupService 的自恢复以及 MainActivity 每次进入时的隐式启动。
        if (!appPreferences.isTrackingEnabled) {
            AppLog.d(TAG, "用户已关闭追踪，跳过启动定位服务")
            return
        }
        if (!serviceBound) {
            val intent = Intent(this, GeoFenceLocationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            AppLog.d(TAG, "启动定位服务")
        }
    }

    /**
     * 切换追踪开关（设置页调用）。
     *
     * 统一收口"用户意图 + 服务实际状态"：
     * - 开启：写入意图 → 启动服务
     * - 关闭：写入意图 → 停止服务并取消 Alarm
     */
    fun setTrackingEnabled(enabled: Boolean) {
        appPreferences.isTrackingEnabled = enabled
        AppLog.d(TAG, "追踪开关: $enabled")
        if (enabled) {
            startLocationService()
        } else {
            stopLocationService()
        }
    }

    fun stopLocationService() {
        // 用户主动停止追踪：先取消 AlarmManager 唤醒，否则服务已经停了，
        // WakeupReceiver 仍会每 8 分钟准时触发、WakeupService 空转三次后退出，
        // 白耗电并刷日志。
        // 注意：这里只在用户显式停止时取消。服务被系统回收走的是另一条路径，
        // 那时必须保留 Alarm 才能把主服务拉回来（见 WakeupService.handleServiceNotBound）。
        locationService?.cancelWakeupAlarm()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, GeoFenceLocationService::class.java))
        AppLog.d(TAG, "停止定位服务")
    }

    fun getLocationService(): GeoFenceLocationService? = locationService

    // ============================================================
    // 围栏处理（委托给 FenceProcessingHolder）
    // ============================================================

    fun checkGeoFences(location: Location, accel: com.trackmaster.app.core.AccelerometerData? = null): Boolean =
        fenceProcessingHolder.checkGeoFences(location, accel)

    fun handleFirstLocationAfterRestore(location: Location) =
        fenceProcessingHolder.handleFirstLocationAfterRestore(location)

    fun saveStateOnAppClose() = fenceProcessingHolder.saveStateOnAppClose()
    fun getVacuumDuration(): Long? = fenceProcessingHolder.getVacuumDuration()
    fun getLastExitFence() = fenceProcessingHolder.getLastExitFence()

    fun refreshEngineFences() {
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        engineHolder.refresh(coreFences)
        // 【P1修复】围栏数据已变更，必须同时清除 Repository 的 segmentCache/summaryCache
        // 否则统计页仍会读到旧围栏结构下的 Segment/日汇总数据（README 要求的两步刷新，此前只做了第一步）
        // clearCache() 是普通函数（非 suspend），可直接在同步函数中调用
        repository.clearCache()
        appScope.launch {
            engine.init()
        }
        repositoryHolder.notifyFenceStateChanged()
    }

    fun checkAndUpdateFenceState() = fenceProcessingHolder.checkAndUpdateFenceState()

    fun updateEngineConfig() {
        // 【P1修复】基于当前生效的 config 做 copy 修改，而不是用全新默认值重建。
        // 原实现只填 3 个字段，其余字段（stationaryAccuracyMultiplier、
        // stationaryAccelVariance、stationarySpeedThreshold、stationaryWindowSize、
        // stationaryMinPoints、throttleSteps、resetDistanceM、slcDriftM、
        // baseIntervalMs、consecutiveMovementThreshold）会被静默重置成默认值。
        val scheduler = engineHolder.engine.getScheduler()
        val newConfig = scheduler.config.copy(
            driftThresholdM = appPreferences.driftThreshold,
            stationaryMinDistanceM = appPreferences.stationaryThreshold,
            boundaryBufferM = appPreferences.boundaryBuffer
        )
        // 同步给 EngineHolder，避免后续 refresh() 重建 Engine 时退回旧配置
        engineHolder.updateThrottleConfig(newConfig)
    }

    // ============================================================
    // 状态恢复
    // ============================================================

    private fun restorePersistentState() {
        val lastAppCloseTime = persistentStateManager.getLastAppCloseTime()
        val currentFenceState = persistentStateManager.getCurrentFenceState()
        val lastExitState = persistentStateManager.getLastExitState()
        val lastLocation = persistentStateManager.getLastLocation()

        AppLog.d(TAG, "恢复持久化状态:")
        AppLog.d(TAG, "  上次应用关闭时间: $lastAppCloseTime")
        AppLog.d(TAG, "  当前围栏状态: $currentFenceState")
        AppLog.d(TAG, "  上次离开状态: $lastExitState")
        AppLog.d(TAG, "  上次位置: $lastLocation")

        if (currentFenceState == null && lastExitState == null) {
            AppLog.d(TAG, "没有持久化状态需要恢复")
            return
        }
        stayDurationManager.loadActiveSessionsFromDatabase()
        if (currentFenceState != null) {
            AppLog.d(TAG, "等待首次定位以恢复围栏状态: ${currentFenceState.fenceName}")
        }
    }

    // ============================================================
    // 数据导入导出
    // ============================================================

    fun exportFencesToJson(fences: List<GeoFence>): String? =
        dbHelper.generateFencesJson(fences)

    fun importFencesAndRefresh(jsonContent: String, preserveId: Boolean = false): Int {
        val count = dbHelper.importFencesFromJson(jsonContent, preserveId)
        if (count >= 0) {
            geoFences.clear()
            geoFences.addAll(dbHelper.getAllFences())
            refreshEngineFences()
            checkAndUpdateFenceState()
        }
        return count
    }
}
