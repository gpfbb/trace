package com.trackmaster.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.amap.api.location.AMapLocation
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

    private var locationService: GeoFenceLocationService? = null
    private var serviceBound = false

    val geoFences = mutableListOf<GeoFence>()
    val categories = mutableListOf<Category>()
    val fenceEnterTimes = mutableMapOf<String, Date>()
    val fenceStayRecords = mutableListOf<FenceStayRecord>()
    val geoFenceEvents = mutableListOf<GeoFenceEvent>()
    val currentFenceStates = mutableMapOf<String, Boolean>()
    val stableLocationCount = mutableMapOf<String, Int>()

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
        Log.d(TAG, "应用创建，初始化数据库")

        loadDataFromDatabase()
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
        val userPoint = GeoFencePoint(location.latitude, location.longitude)

        if (location.accuracy > 50f) {
            Log.d(TAG, "位置精度不足: ${location.accuracy}m，跳过围栏检测")
            return
        }

        for (fence in geoFences) {
            val isInside = when (fence.type) {
                GeoFenceType.CIRCLE -> isPointInCircle(userPoint, fence)
                GeoFenceType.POLYGON -> isPointInPolygon(userPoint, fence)
            }

            val previousState = currentFenceStates[fence.id] ?: false
            val currentCount = stableLocationCount[fence.id] ?: 0

            if (isInside) {
                if (!previousState) {
                    stableLocationCount[fence.id] = currentCount + 1
                    if (stableLocationCount[fence.id]!! >= 3) {
                        recordFenceEnter(fence, location)
                        currentFenceStates[fence.id] = true
                        stableLocationCount[fence.id] = 0
                    }
                }
            } else {
                if (previousState) {
                    stableLocationCount[fence.id] = currentCount + 1
                    if (stableLocationCount[fence.id]!! >= 3) {
                        recordFenceExit(fence, location)
                        currentFenceStates[fence.id] = false
                        stableLocationCount[fence.id] = 0
                    }
                } else {
                    stableLocationCount[fence.id] = 0
                }
            }
        }
    }

    private fun recordFenceEnter(fence: GeoFence, location: AMapLocation) {
        val now = Date()
        fenceEnterTimes[fence.id] = now
        Log.d(TAG, "进入围栏: ${fence.name} at $now")

        val event = GeoFenceEvent(
            fenceId = fence.id,
            fenceName = fence.name,
            action = GeoFenceAction.ENTER,
            time = now,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy
        )
        geoFenceEvents.add(event)
        dbHelper.insertEvent(event)
    }

    private fun recordFenceExit(fence: GeoFence, location: AMapLocation) {
        val now = Date()
        val enterTime = fenceEnterTimes[fence.id]
        val durationSeconds = if (enterTime != null) {
            (now.time - enterTime.time) / 1000
        } else null

        if (enterTime != null) {
            val record = FenceStayRecord(
                fenceId = fence.id,
                fenceName = fence.name,
                enterTime = enterTime,
                exitTime = now,
                durationSeconds = durationSeconds
            )
            fenceStayRecords.add(record)
            dbHelper.insertStayRecord(record)
        }

        val event = GeoFenceEvent(
            fenceId = fence.id,
            fenceName = fence.name,
            action = GeoFenceAction.EXIT,
            time = now,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            durationSeconds = durationSeconds
        )
        geoFenceEvents.add(event)
        dbHelper.insertEvent(event)
        Log.d(TAG, "离开围栏: ${fence.name} at $now, 停留: ${durationSeconds}秒")

        fenceEnterTimes.remove(fence.id)
    }

    private fun isPointInCircle(point: GeoFencePoint, fence: GeoFence): Boolean {
        val center = fence.center ?: return false
        val radius = fence.radius ?: return false
        val distance = calculateDistance(point, center)
        return distance <= radius
    }

    private fun isPointInPolygon(point: GeoFencePoint, fence: GeoFence): Boolean {
        val points = fence.points ?: return false
        if (points.size < 3) return false

        var inside = false
        val n = points.size

        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = points[i].latitude
            val yi = points[i].longitude
            val xj = points[j].latitude
            val yj = points[j].longitude

            if (((yi > point.longitude) != (yj > point.longitude)) &&
                (point.latitude < (xj - xi) * (point.longitude - yi) / (yj - yi) + xi)) {
                inside = !inside
            }
        }

        return inside
    }

    private fun calculateDistance(p1: GeoFencePoint, p2: GeoFencePoint): Float {
        val earthRadius = 6371000.0

        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(p1.latitude)) * Math.cos(Math.toRadians(p2.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return (earthRadius * c).toFloat()
    }
}
