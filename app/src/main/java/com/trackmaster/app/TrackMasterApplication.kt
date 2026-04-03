package com.trackmaster.app

import android.app.Application
import android.os.StrictMode
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TrackMasterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 高德地图 SDK 隐私合规声明
        // 在使用任何高德 SDK 之前，必须先调用这两个方法声明隐私政策
        
        // 定位SDK隐私合规
        AMapLocationClient.updatePrivacyShow(applicationContext, true, true)
        AMapLocationClient.updatePrivacyAgree(applicationContext, true)
        
        // 地图SDK隐私合规
        MapsInitializer.updatePrivacyShow(applicationContext, true, true)
        MapsInitializer.updatePrivacyAgree(applicationContext, true)
        
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
