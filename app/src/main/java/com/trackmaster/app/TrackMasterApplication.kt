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
import java.util.Date

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

    private var locationService: GeoFenceLocationService? = null
    private var serviceBound = false

    val geoFences = mutableListOf<GeoFence>()
    val categories = mutableListOf<Category>()
    val fenceEnterTimes = mutableMapOf<String, Date>()
    val fenceStayRecords = mutableListOf<FenceStayRecord>()
    val geoFenceEvents = mutableListOf<GeoFenceEvent>()

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

    fun checkGeoFences(location: AMapLocation) {
        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            timestamp = location.time,
        )

        val closedSeg = kotlinx.coroutines.runBlocking {
            engine.onLocationUpdate(fix)
        }

        if (closedSeg != null) {
            val fenceName = geoFences.find { it.id == closedSeg.leafGeofenceId }?.name ?: closedSeg.leafGeofenceId
            if (engine.currentLeafId != null) {
                // 围栏切换：退出旧 → 进入新
                Log.d(TAG, "离开围栏: $fenceName")
                fenceEnterTimes.remove(closedSeg.leafGeofenceId)
            } else {
                // 完全离开所有围栏
                Log.d(TAG, "离开围栏: $fenceName")
                fenceEnterTimes.remove(closedSeg.leafGeofenceId)
            }
            val enterFenceId = engine.currentLeafId
            if (enterFenceId != null) {
                val enterFenceName = geoFences.find { it.id == enterFenceId }?.name ?: enterFenceId
                fenceEnterTimes[enterFenceId] = Date(location.time)
                Log.d(TAG, "进入围栏: $enterFenceName")
            }
        }
    }

    /** 刷新引擎围栏数据（围栏增删后调用） */
    fun refreshEngineFences() {
        val coreFences = fenceTreeManager.loadFenceTree(geoFences)
        engine = Engine(coreFences, repository)
        aggregator = Aggregator(coreFences, repository)
        kotlinx.coroutines.runBlocking { engine.init() }
    }

}
