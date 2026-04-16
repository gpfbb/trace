package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.AMap
import com.trackmaster.app.mapstraction.amap.AMapPresenter
import com.trackmaster.app.LocationServiceState
import com.trackmaster.app.ui.navigation.TrackMasterNavHost
import com.trackmaster.app.ui.theme.TrackMasterTheme
import com.trackmaster.app.utils.LanguageManager

class MainActivity : ComponentActivity() {

    private var aMapPresenter: AMapPresenter? = null

    fun setMapPresenter(presenter: AMapPresenter?) {
        this.aMapPresenter = presenter
    }

    fun getCurrentMap(): AMap? = aMapPresenter?.getAMapInternal()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            startLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply locale from preferences on every cold start
        val appPrefs = (application as TrackMasterApplication).appPreferences
        android.util.Log.d("MainActivity", "onCreate: appPrefs.appLanguage=${appPrefs.appLanguage}")
        LanguageManager.ensureLocale(this)

        if (appPrefs.mapProvider == MapProvider.AMAP) {
            Log.d("TrackMaster", "初始化高德 SDK")
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
        }

        setContent {
            TrackMasterTheme {
                TrackMasterNavHost()
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val params = window.attributes
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val method = window.javaClass.getMethod(
                        "setPreferredFrameRate",
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(window, 90f, 1)
                } else {
                    val mode = display?.supportedModes?.find { it.refreshRate <= 90f }
                    if (mode != null) {
                        params.preferredDisplayModeId = mode.modeId
                        window.attributes = params
                    }
                }
            } catch (e: Exception) {
                Log.w("TrackMaster", "无法设置帧率限制: ${e.message}")
            }
        }

        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("TrackMaster", "请求定位权限")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            Log.d("TrackMaster", "已有定位权限，直接启动定位")
            startLocation()
        }
    }

    private var locationServiceListener: ((com.trackmaster.app.LocationServiceState) -> Unit)? = null

    private fun startLocation() {
        try {
            Log.d("TrackMaster", "启动定位服务")
            val app = application as TrackMasterApplication
            app.startLocationService()
            Handler(Looper.getMainLooper()).postDelayed({
                setupLocationServiceListener()
            }, 500)
        } catch (e: Exception) {
            Log.e("TrackMaster", "启动定位失败", e)
        }
    }

    private fun setupLocationServiceListener() {
        val app = application as TrackMasterApplication
        val service = app.getLocationService()

        if (service != null) {
            locationServiceListener?.let { service.removeStateListener(it) }
            locationServiceListener = { state ->
                runOnUiThread {
                    Log.d("TrackMaster", "位置更新: ${state.locationCount}次, isRunning=${state.isRunning}")
                }
            }
            locationServiceListener?.let { service.addStateListener(it) }
            Log.d("TrackMaster", "已设置定位服务状态监听")
        } else {
            Log.w("TrackMaster", "定位服务尚未绑定，稍后重试")
            Handler(Looper.getMainLooper()).postDelayed({
                setupLocationServiceListener()
            }, 1000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as TrackMasterApplication
        app.saveStateOnAppClose()
        val service = app.getLocationService()
        locationServiceListener?.let { listener ->
            service?.removeStateListener(listener)
        }
    }
}


