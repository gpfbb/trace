package com.trackmaster.app.architecture

import android.app.Application
import android.util.Log
import com.trackmaster.app.GeoFenceDatabaseHelper
import com.trackmaster.app.architecture.data.datasources.*
import com.trackmaster.app.architecture.data.repositories.*
import com.trackmaster.app.architecture.domain.usecases.*
import com.trackmaster.app.architecture.domain.models.Geofence
import com.trackmaster.app.architecture.common.utils.ThrottleConfig
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 应用程序类 - 管理所有依赖关系和初始化
 */
class TrackMasterApp : Application() {
    companion object {
        private const val TAG = "TrackMasterApp"
        lateinit var instance: TrackMasterApp
            private set
    }

    // 数据库
    private lateinit var dbHelper: GeoFenceDatabaseHelper

    // 数据源
    private lateinit var geofenceDataSource: GeofenceDataSource
    private lateinit var categoryDataSource: CategoryDataSource
    private lateinit var eventDataSource: EventDataSource
    private lateinit var stayRecordDataSource: StayRecordDataSource
    private lateinit var dailyStayDataSource: DailyStayDataSource
    private lateinit var segmentDataSource: SegmentDataSource

    // 仓库
    private lateinit var geofenceRepository: GeofenceRepository
    private lateinit var eventRepository: EventRepository
    private lateinit var stayRecordRepository: StayRecordRepository
    private lateinit var statisticsRepository: StatisticsRepository
    private lateinit var segmentRepository: SegmentRepository

    // 用例
    private lateinit var geofenceManagementUseCase: GeofenceManagementUseCase
    private lateinit var locationProcessingUseCase: LocationProcessingUseCase
    private lateinit var stayDurationUseCase: StayDurationUseCase
    private lateinit var statisticsUseCase: StatisticsUseCase
    private lateinit var dataManagementUseCase: DataManagementUseCase

    // 引擎和服务
    lateinit var fenceTrackingEngine: FenceTrackingEngine
        private set
    lateinit var locationService: LocationService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initializeComponents()
        Log.d(TAG, "应用初始化完成")
    }

    /**
     * 初始化所有组件
     */
    private fun initializeComponents() {
        // 初始化数据库
        dbHelper = GeoFenceDatabaseHelper(this)

        // 初始化数据源
        geofenceDataSource = SQLiteGeofenceDataSource(dbHelper)
        categoryDataSource = SQLiteCategoryDataSource(dbHelper)
        eventDataSource = SQLiteEventDataSource(dbHelper)
        stayRecordDataSource = SQLiteStayRecordDataSource(dbHelper)
        dailyStayDataSource = SQLiteDailyStayDataSource(dbHelper)
        segmentDataSource = SQLiteSegmentDataSource(dbHelper)

        // 初始化仓库
        geofenceRepository = GeofenceRepository(geofenceDataSource, categoryDataSource)
        eventRepository = EventRepository(eventDataSource)
        stayRecordRepository = StayRecordRepository(stayRecordDataSource, dailyStayDataSource)
        statisticsRepository = StatisticsRepository(dailyStayDataSource)
        segmentRepository = SegmentRepository(segmentDataSource)

        // 初始化用例
        geofenceManagementUseCase = GeofenceManagementUseCase(geofenceRepository)
        locationProcessingUseCase = LocationProcessingUseCase(
            geofenceRepository,
            eventRepository,
            stayRecordRepository,
            segmentRepository
        )
        stayDurationUseCase = StayDurationUseCase(stayRecordRepository)
        statisticsUseCase = StatisticsUseCase(statisticsRepository)
        dataManagementUseCase = DataManagementUseCase(eventRepository, stayRecordRepository)

        // 初始化引擎和服务
        val fences = loadFences()
        fenceTrackingEngine = FenceTrackingEngine(
            fences,
            segmentRepository,
            ThrottleConfig()
        )
        locationService = LocationService(this, fenceTrackingEngine)

        // 初始化引擎
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            fenceTrackingEngine.initialize()
        }
    }

    /**
     * 加载围栏数据
     */
    private fun loadFences(): List<Geofence> {
        return try {
            kotlinx.coroutines.runBlocking { 
                geofenceRepository.getAllFences()
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载围栏数据失败", e)
            emptyList()
        }
    }

    /**
     * 获取用例实例
     */
    fun getGeofenceManagementUseCase(): GeofenceManagementUseCase = geofenceManagementUseCase
    fun getLocationProcessingUseCase(): LocationProcessingUseCase = locationProcessingUseCase
    fun getStayDurationUseCase(): StayDurationUseCase = stayDurationUseCase
    fun getStatisticsUseCase(): StatisticsUseCase = statisticsUseCase
    fun getDataManagementUseCase(): DataManagementUseCase = dataManagementUseCase

    /**
     * 启动定位服务
     */
    fun startLocationService() {
        locationService.start()
    }

    /**
     * 停止定位服务
     */
    fun stopLocationService() {
        locationService.stop()
    }

    /**
     * 应用终止时调用
     */
    fun onAppTerminate() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            fenceTrackingEngine.onAppTerminate()
        }
        stopLocationService()
    }
}