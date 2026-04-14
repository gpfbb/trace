package com.trackmaster.app.mapstraction.amap

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.MapView
import com.trackmaster.app.mapstraction.MapPresenter
import com.trackmaster.app.mapstraction.MapProvider

class AMapProvider : MapProvider {

    override fun createMapPresenter(mapView: Any): MapPresenter {
        require(mapView is MapView) { "mapView must be MapView instance" }
        throw NotImplementedError("Use createMapPresenterWithLifecycle instead")
    }

    fun createMapPresenterWithLifecycle(
        mapView: MapView,
        lifecycleOwner: LifecycleOwner
    ): MapPresenter {
        return AMapPresenter(mapView, lifecycleOwner)
    }

    fun createMapView(context: Context): MapView {
        return MapView(context, AMapOptions().apply {
            zoomControlsEnabled(true)
            compassEnabled(true)
            scaleControlsEnabled(true)
        })
    }

    override fun getProviderName(): String = "amap"
}
