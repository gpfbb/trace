package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.trackmaster.app.utils.AppLog
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

    /**
     * 通知权限申请（Android 13+）
     * 前台服务的常驻通知依赖 POST_NOTIFICATIONS，未授权时用户看不到通知，系统也更容易杀服务
     */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        if (granted) {
            AppLog.d("MainActivity", "通知权限已授予")
        } else {
            AppLog.w("MainActivity", "通知权限被拒绝，前台服务通知可能不可见")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply locale from preferences on every cold start
        val appPrefs = (application as TrackMasterApplication).appPreferences
        AppLog.d("MainActivity", "onCreate: appPrefs.appLanguage=${appPrefs.appLanguage}")
        LanguageManager.ensureLocale(this)

        if (appPrefs.mapProvider == MapProvider.AMAP) {
            AppLog.d("TrackMaster", "初始化高德 SDK")
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
                AppLog.w("TrackMaster", "无法设置帧率限制: ${e.message}")
            }
        }

        requestLocationPermission()
        // Android 13 以下不需要运行时授权（声明权限即可），只有 13+ 才弹窗申请
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }
    }

    private fun requestLocationPermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AppLog.d("TrackMaster", "请求定位权限")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            AppLog.d("TrackMaster", "已有定位权限，直接启动定位")
            startLocation()
        }
    }

    private fun requestNotificationPermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            AppLog.d("TrackMaster", "请求通知权限")
            notificationPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            AppLog.d("TrackMaster", "已有通知权限")
        }
    }

    private var locationServiceListener: ((com.trackmaster.app.LocationServiceState) -> Unit)? = null
    private val locationSetupHandler = Handler(Looper.getMainLooper())
    private var locationSetupRetries = 0
    private val locationSetupRetryRunnable = Runnable { setupLocationServiceListener() }

    private fun startLocation() {
        try {
            AppLog.d("TrackMaster", "启动定位服务")
            val app = application as TrackMasterApplication
            app.startLocationService()
            locationSetupHandler.postDelayed(locationSetupRetryRunnable, 500)
        } catch (e: Exception) {
            AppLog.e("TrackMaster", "启动定位失败", e)
        }
    }

    private fun setupLocationServiceListener() {
        val app = application as TrackMasterApplication
        val service = app.getLocationService()

        if (service != null) {
            locationServiceListener?.let { service.removeStateListener(it) }
            locationServiceListener = { state ->
                runOnUiThread {
                    AppLog.d("TrackMaster", "位置更新: ${state.locationCount}次, isRunning=${state.isRunning}")
                }
            }
            locationSetupRetries = 0
            locationServiceListener?.let { service.addStateListener(it) }
            AppLog.d("TrackMaster", "已设置定位服务状态监听")
        } else if (locationSetupRetries < 5) {
            locationSetupRetries++
            AppLog.w("TrackMaster", "定位服务尚未绑定，${locationSetupRetries}/5 次重试")
            locationSetupHandler.postDelayed(locationSetupRetryRunnable, 1000)
        } else {
            AppLog.e("TrackMaster", "定位服务绑定失败，已达最大重试次数")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationSetupHandler.removeCallbacks(locationSetupRetryRunnable)
        val app = application as TrackMasterApplication
        app.saveStateOnAppClose()
        val service = app.getLocationService()
        locationServiceListener?.let { listener ->
            service?.removeStateListener(listener)
        }
    }
}
