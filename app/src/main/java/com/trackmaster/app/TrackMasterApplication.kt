package com.trackmaster.app

import android.app.Application
import android.os.StrictMode
import com.amap.api.location.AMapLocationClient
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TrackMasterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
