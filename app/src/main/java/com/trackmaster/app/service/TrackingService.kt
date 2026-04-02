package com.trackmaster.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.trackmaster.app.MainActivity
import com.trackmaster.app.R
import com.trackmaster.app.data.entity.TrackPoint
import com.trackmaster.app.data.entity.TrackSession
import com.trackmaster.app.data.entity.StayPoint
import com.trackmaster.app.data.repository.TrackingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : Service() {

    @Inject
    lateinit var locationService: LocationService

    @Inject
    lateinit var geofenceService: GeofenceService

    @Inject
    lateinit var repository: TrackingRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pointBuffer = mutableListOf<TrackPoint>()
    private var currentSessionId: Long? = null
    private var totalDistance = 0f
    private var lastPoint: TrackPoint? = null
    private var isTracking = false
    private var isPaused = false
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START = "com.trackmaster.app.ACTION_START"
        const val ACTION_PAUSE = "com.trackmaster.app.ACTION_PAUSE"
        const val ACTION_RESUME = "com.trackmaster.app.ACTION_RESUME"
        const val ACTION_STOP = "com.trackmaster.app.ACTION_STOP"

        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tracking_channel"

        fun startService(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseService(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeService(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        locationService.init()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_PAUSE -> pauseTracking()
            ACTION_RESUME -> resumeTracking()
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (isTracking) return

        serviceScope.launch {
            val session = TrackSession(startTime = System.currentTimeMillis())
            currentSessionId = repository.insertSession(session)
            totalDistance = 0f
            lastPoint = null
            pointBuffer.clear()
            isTracking = true
            isPaused = false

            locationService.startLocation()
            locationService.locationData.onEach { data ->
                data?.let { handleLocationUpdate(it) }
            }.launchIn(serviceScope)

            updateNotification()
        }
    }

    private fun pauseTracking() {
        if (!isTracking || isPaused) return
        isPaused = true
        flushBuffer()
        updateNotification()
    }

    private fun resumeTracking() {
        if (!isTracking || !isPaused) return
        isPaused = false
        updateNotification()
    }

    private fun stopTracking() {
        flushBuffer()
        serviceScope.launch {
            currentSessionId?.let { id ->
                repository.getSessionById(id)?.let { session ->
                    val updatedSession = session.copy(
                        endTime = System.currentTimeMillis(),
                        isCompleted = true,
                        totalDistance = totalDistance
                    )
                    repository.updateSession(updatedSession)
                }
            }
        }
        isTracking = false
        isPaused = false
        locationService.stopLocation()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun handleLocationUpdate(data: LocationData) {
        if (!isTracking || isPaused) return

        val location = data.location ?: return
        val sessionId = currentSessionId ?: return

        val point = TrackPoint(
            sessionId = sessionId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            speed = location.speed,
            bearing = location.bearing,
            accuracy = location.accuracy,
            timestamp = System.currentTimeMillis(),
            isValid = true
        )

        if (lastPoint != null) {
            val distance = calculateDistance(lastPoint!!, point)
            if (distance < 5f) {
                return
            }
            totalDistance += distance
        }

        lastPoint = point
        pointBuffer.add(point)

        geofenceService.checkGeofences(LatLng(location.latitude, location.longitude))

        if (pointBuffer.size >= 10) {
            flushBuffer()
        }

        updateNotification()
    }

    private fun calculateDistance(p1: TrackPoint, p2: TrackPoint): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            p1.latitude, p1.longitude,
            p2.latitude, p2.longitude,
            results
        )
        return results[0]
    }

    private fun flushBuffer() {
        if (pointBuffer.isEmpty()) return
        serviceScope.launch {
            repository.insertPoints(pointBuffer.toList())
            pointBuffer.clear()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "轨迹记录服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "持续记录您的运动轨迹"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val stateText = if (isPaused) "已暂停" else "记录中"
        val distanceText = String.format("%.2f km", totalDistance / 1000f)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TrackingService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TrackMaster - $stateText")
            .setContentText("里程：$distanceText")
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "继续" else "暂停",
                if (isPaused) resumeIntent else pauseIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TrackMaster:TrackingWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTracking) {
            stopTracking()
        }
        serviceScope.cancel()
        locationService.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
