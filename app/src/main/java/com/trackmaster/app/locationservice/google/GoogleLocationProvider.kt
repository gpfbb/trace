package com.trackmaster.app.locationservice.google

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location as AndroidLocation
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback as GmsLocationCallback
import com.google.android.gms.location.LocationRequest as GmsLocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trackmaster.app.locationservice.Location
import com.trackmaster.app.locationservice.LocationCallback
import com.trackmaster.app.locationservice.LocationMode
import com.trackmaster.app.locationservice.LocationProvider
import com.trackmaster.app.locationservice.LocationType

class GoogleLocationProvider(
    private val context: Context
) : LocationProvider {

    companion object {
        private const val TAG = "GoogleLocationProvider"
        private const val HIGH_ACCURACY_INTERVAL_MS = 5000L
        private const val BATTERY_SAVING_INTERVAL_MS = 10000L
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var appCallback: LocationCallback? = null
    private var currentMode: LocationMode = LocationMode.HIGH_ACCURACY
    private var currentIntervalMs: Long = HIGH_ACCURACY_INTERVAL_MS
    private var _lastLocation: Location? = null
    private var isRequesting = false

    private val gmsCallback = object : GmsLocationCallback() {
        override fun onLocationResult(result: GmsLocationResult) {
            val gmsLoc = result.lastLocation ?: run {
                Log.w(TAG, "Google location result is null (failure)")
                return
            }
            val location = convertLocation(gmsLoc)
            _lastLocation = location
            appCallback?.onLocationChanged(location)
            Log.d(TAG, "Google location: (${location.latitude},${location.longitude}) acc=${location.accuracy}m type=${location.locationType}")
        }
    }

    override fun getLastLocation(): Location? = _lastLocation

    override fun isRunning(): Boolean = isRequesting

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isRequesting) return
        try {
            val request = createGmsRequest(currentMode)
            fusedClient.requestLocationUpdates(request, gmsCallback, Looper.getMainLooper())
            isRequesting = true
            Log.d(TAG, "GoogleLocationProvider started")
        } catch (e: Exception) {
            Log.e(TAG, "启动定位失败", e)
        }
    }

    override fun stop() {
        if (!isRequesting) return
        try {
            fusedClient.removeLocationUpdates(gmsCallback)
            isRequesting = false
            Log.d(TAG, "GoogleLocationProvider stopped")
        } catch (e: Exception) {
            Log.e(TAG, "停止定位失败", e)
        }
    }

    override fun setLocationCallback(callback: LocationCallback) {
        this.appCallback = callback
    }

    override fun removeLocationCallback() {
        this.appCallback = null
    }

    override fun setLocationMode(mode: LocationMode) {
        if (currentMode == mode) return
        val wasRunning = isRequesting
        if (wasRunning) stop()
        currentMode = mode
        if (wasRunning) start()
        Log.d(TAG, "Location mode changed to $mode")
    }

    override fun setInterval(intervalMs: Long) {
        if (currentIntervalMs == intervalMs) return
        currentIntervalMs = intervalMs
        if (isRequesting) {
            stop()
            start()
        }
        Log.d(TAG, "Location interval changed to ${intervalMs}ms")
    }

    override fun getProviderName(): String = "Google"

    @SuppressLint("MissingPermission")
    private fun createGmsRequest(mode: LocationMode): GmsLocationRequest {
        val priority = when (mode) {
            LocationMode.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
            LocationMode.BATTERY_SAVING -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocationMode.DEVICE_ONLY -> Priority.PRIORITY_HIGH_ACCURACY
        }
        return GmsLocationRequest.Builder(currentIntervalMs)
            .setPriority(priority)
            .setMinUpdateIntervalMillis(currentIntervalMs / 2)
            .setMaxUpdates(Int.MAX_VALUE)
            .build()
    }

    private fun convertLocation(gmsLoc: AndroidLocation): Location {
        val type = if (gmsLoc.hasAccuracy() && gmsLoc.accuracy < 50f) LocationType.GPS else LocationType.NETWORK
        return Location(
            latitude = gmsLoc.latitude,
            longitude = gmsLoc.longitude,
            accuracy = gmsLoc.accuracy,
            speed = gmsLoc.speed,
            bearing = gmsLoc.bearing,
            timestamp = gmsLoc.time,
            locationType = type
        )
    }

    fun destroy() = stop()
}
