package com.trackmaster.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.core.EngineHolder
import com.trackmaster.app.core.FenceProcessingHolder
import com.trackmaster.app.core.FenceTreeManager
import com.trackmaster.app.core.ManagersHolder
import com.trackmaster.app.core.RepositoryHolder
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.di.appModule
import com.trackmaster.app.services.Aggregator
import com.trackmaster.app.ui.theme.ThemeManager
import com.trackmaster.app.utils.ThrottleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    val dbHelper: GeoFenceDatabaseHelper by lazy { getKoin().get<GeoFenceDatabaseHelper>() }

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

    lateinit var aggregator: Aggregator
        private set

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
    val fenceStayRecords get() = repositoryHolder.fenceStayRecords
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

    private val appScope = CoroutineScope(Dispatchers.IO)

    // ============================================================
    // 定位服务
    // ============================================================

    private var locationService: GeoFenceLocationService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "定位服务已连接")
            locationService = (service as? GeoFenceLocationService.LocalBinder)?.service
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "定位服务已断开")
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

        ThemeManager.init(this)
        Log.d(TAG, "主题初始化完成: ${ThemeManager.getCurrentTheme()}")

        startKoin {
            androidLogger()
            androidContext(this@TrackMasterApplication)
            modules(appModule)
        }
        Log.d(TAG, "Koin 依赖注入初始化完成")

        managersHolder.init(this)
        Log.d(TAG, "应用创建，初始化数据库")

        loadDataFromDatabase()

        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        engineHolder.initialize(coreFences)
        aggregator = Aggregator(coreFences, repository)
        Log.d(TAG, "引擎初始化完成: ${coreFences.size} 个核心围栏")

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

    private fun loadDataFromDatabase() {
        try {
            categories.clear()
            categories.addAll(dbHelper.getAllCategories())
            geoFences.clear()
            geoFences.addAll(dbHelper.getAllFences())
            geoFenceEvents.clear()
            geoFenceEvents.addAll(dbHelper.getEventsByTimeRange(Date(0), Date()))
            fenceStayRecords.clear()
            fenceStayRecords.addAll(dbHelper.getAllStayRecords())
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
        stopService(Intent(this, GeoFenceLocationService::class.java))
        Log.d(TAG, "停止定位服务")
    }

    fun getLocationService(): GeoFenceLocationService? = locationService

    // ============================================================
    // 围栏处理（委托给 FenceProcessingHolder）
    // ============================================================

    fun checkGeoFences(location: Location): Boolean =
        fenceProcessingHolder.checkGeoFences(location)

    fun handleFirstLocationAfterRestore(location: Location) =
        fenceProcessingHolder.handleFirstLocationAfterRestore(location)

    fun saveStateOnAppClose() = fenceProcessingHolder.saveStateOnAppClose()
    fun getVacuumDuration(): Long? = fenceProcessingHolder.getVacuumDuration()
    fun getLastExitFence() = fenceProcessingHolder.getLastExitFence()

    fun refreshEngineFences() {
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        engineHolder.refresh(coreFences)
        aggregator = Aggregator(coreFences, repository)
        appScope.launch { engine.init() }
        repositoryHolder.notifyFenceStateChanged()
    }

    fun checkAndUpdateFenceState() = fenceProcessingHolder.checkAndUpdateFenceState()

    fun updateEngineConfig() {
        val newConfig = ThrottleConfig(
            driftThresholdM = appPreferences.driftThreshold,
            stationaryMinDistanceM = appPreferences.stationaryThreshold,
            boundaryBufferM = appPreferences.boundaryBuffer
        )
        engineHolder.engine.getScheduler().updateConfig(newConfig)
    }

    // ============================================================
    // 状态恢复
    // ============================================================

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

        if (currentFenceState == null && lastExitState == null) {
            Log.d(TAG, "没有持久化状态需要恢复")
            return
        }
        stayDurationManager.loadActiveSessionsFromDatabase()
        if (currentFenceState != null) {
            Log.d(TAG, "等待首次定位以恢复围栏状态: ${currentFenceState.fenceName}")
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
