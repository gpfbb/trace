package com.trackmaster.app.locationservice

/**
 * 位置服务接口
 */
interface LocationProvider {

    interface State {
        val isRunning: Boolean
        val lastLocation: Location?
        val locationCount: Int
    }

    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun getLastLocation(): Location?
    fun setLocationCallback(callback: LocationCallback)
    fun removeLocationCallback()
    fun setLocationMode(mode: LocationMode)
    fun setInterval(intervalMs: Long)
    fun getProviderName(): String
}

enum class LocationMode {
    HIGH_ACCURACY,
    BATTERY_SAVING,
    DEVICE_ONLY
}
