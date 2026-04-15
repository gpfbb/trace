package com.trackmaster.app.mapstraction.google

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.MapView
import com.trackmaster.app.mapstraction.MapPresenter
import com.trackmaster.app.mapstraction.MapProvider

class GoogleMapProvider : MapProvider {

    override fun createMapPresenter(mapView: Any): MapPresenter {
        require(mapView is MapView) { "mapView must be MapView instance" }
        throw NotImplementedError("Use createMapPresenterWithLifecycle instead")
    }

    fun createMapPresenterWithLifecycle(
        mapView: MapView,
        lifecycleOwner: LifecycleOwner
    ): MapPresenter {
        return GoogleMapPresenter(mapView, lifecycleOwner.lifecycle)
    }

    override fun getProviderName(): String = "google"
}
