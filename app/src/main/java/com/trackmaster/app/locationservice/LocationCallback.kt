package com.trackmaster.app.locationservice

/**
 * 位置更新回调接口
 */
interface LocationCallback {
    fun onLocationChanged(location: Location)
    fun onLocationError(errorCode: Int, errorInfo: String)
}
