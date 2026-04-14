package com.trackmaster.app.mapstraction

/**
 * 地图Provider接口 - 用于创建MapPresenter实例
 */
interface MapProvider {
    fun createMapPresenter(mapView: Any): MapPresenter
    fun getProviderName(): String
}
