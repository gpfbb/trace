package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.trackmaster.app.ui.theme.TrackMasterTheme

class MainActivity : ComponentActivity(), AMapLocationListener {

    private var locationClient: AMapLocationClient? = null
    private val currentLocation = mutableStateOf<AMapLocation?>(null)
    private val locationStatus = mutableStateOf("正在初始化...")
    private val locationError = mutableStateOf<String?>(null)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                     permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocation()
        } else {
            locationStatus.value = "权限被拒绝"
            locationError.value = "请授予定位权限"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activityContext = this

        Log.d("TrackMaster", "开始初始化高德 SDK")

        AMapLocationClient.updatePrivacyShow(activityContext, true, true)
        AMapLocationClient.updatePrivacyAgree(activityContext, true)
        AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)

        initLocationClient()

        setContent {
            TrackMasterTheme {
                MainScreen(
                    currentLocation = currentLocation.value,
                    locationStatus = locationStatus.value,
                    locationError = locationError.value
                )
            }
        }

        requestLocationPermission()
    }

    private fun initLocationClient() {
        try {
            Log.d("TrackMaster", "创建定位客户端")
            locationClient = AMapLocationClient(applicationContext)
            locationClient?.setLocationListener(this)

            val option = AMapLocationClientOption()
            option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            option.interval = 2000
            option.isNeedAddress = true
            option.isOnceLocation = false
            option.httpTimeOut = 30000
            locationClient?.setLocationOption(option)

            Log.d("TrackMaster", "定位客户端初始化完成")
        } catch (e: Exception) {
            Log.e("TrackMaster", "初始化定位客户端失败", e)
            locationStatus.value = "初始化失败"
            locationError.value = e.message
        }
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("TrackMaster", "请求定位权限")
            locationStatus.value = "请求权限中..."
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

    private fun startLocation() {
        try {
            Log.d("TrackMaster", "启动定位服务")
            locationStatus.value = "正在获取位置..."
            locationError.value = null
            locationClient?.startLocation()
        } catch (e: Exception) {
            Log.e("TrackMaster", "启动定位失败", e)
            locationStatus.value = "启动失败"
            locationError.value = e.message
        }
    }

    override fun onLocationChanged(location: AMapLocation?) {
        if (location == null) {
            Log.e("TrackMaster", "onLocationChanged: location is null")
            return
        }

        Log.d("TrackMaster", """
            收到位置更新:
            - errorCode: ${location.errorCode}
            - errorInfo: ${location.errorInfo}
            - 经度: ${location.longitude}
            - 纬度: ${location.latitude}
            - 精度: ${location.accuracy}米
            - 地址: ${location.address}
        """.trimIndent())

        if (location.errorCode == 0) {
            currentLocation.value = location
            locationStatus.value = "定位成功"
            locationError.value = null
        } else {
            locationStatus.value = "定位失败"
            locationError.value = "${location.errorInfo} (错误码: ${location.errorCode})"
            Log.e("TrackMaster", "定位错误: ${location.errorInfo}, 错误码: ${location.errorCode}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationClient?.stopLocation()
        locationClient?.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        locationClient?.startLocation()
    }

    override fun onPause() {
        super.onPause()
        locationClient?.stopLocation()
    }
}

@Composable
fun AMapView(
    currentLocation: AMapLocation?,
    onMapReady: (AMap) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context, AMapOptions().apply {
            zoomControlsEnabled(true)
            compassEnabled(true)
            scaleControlsEnabled(true)
        }).apply {
            onCreate(Bundle())
        }
    }

    val mapType = remember { mutableStateOf(AMap.MAP_TYPE_NORMAL) }
    val showTraffic = remember { mutableStateOf(false) }
    val isLayerMenuExpanded = remember { mutableStateOf(false) }
    val hasMovedCamera = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> {
                    mapView.onDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            aMap.uiSettings.isZoomControlsEnabled = true
            aMap.uiSettings.isCompassEnabled = true
            aMap.uiSettings.isScaleControlsEnabled = true
            aMap.uiSettings.isMyLocationButtonEnabled = true

            val myLocationStyle = MyLocationStyle()
            myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            myLocationStyle.interval(2000)
            myLocationStyle.strokeColor(Color.parseColor("#3D9BE9"))
            myLocationStyle.radiusFillColor(Color.argb(50, 63, 155, 233))
            myLocationStyle.strokeWidth(2f)
            aMap.myLocationStyle = myLocationStyle
            aMap.isMyLocationEnabled = true

            aMap.setOnMyLocationChangeListener {
                if (it != null) {
                    Log.d("TrackMaster", "地图蓝点位置变化: ${it.latitude}, ${it.longitude}")
                }
            }

            onMapReady(aMap)

            Log.d("TrackMaster", "地图初始化完成")
        }
    }

    LaunchedEffect(mapType.value, mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            aMap.mapType = mapType.value
        }
    }

    LaunchedEffect(showTraffic.value, mapView) {
        val aMap = mapView.map
        if (aMap != null) {
            aMap.isTrafficEnabled = showTraffic.value
        }
    }

    LaunchedEffect(currentLocation, mapView) {
        val aMap = mapView.map
        if (currentLocation != null && currentLocation.errorCode == 0 && aMap != null) {
            val latLng = LatLng(currentLocation.latitude, currentLocation.longitude)
            // 只在首次定位时移动相机，避免强制居中
            if (!hasMovedCamera.value) {
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                hasMovedCamera.value = true
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize()
            ) { view ->
                view.onResume()
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(200.dp)
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "图层",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { isLayerMenuExpanded.value = !isLayerMenuExpanded.value }) {
                            Text(
                                text = if (isLayerMenuExpanded.value) "▼" else "▶",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (isLayerMenuExpanded.value) {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NORMAL,
                                label = "白昼地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NORMAL }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NIGHT,
                                label = "夜景地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NIGHT }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_SATELLITE,
                                label = "卫星地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_SATELLITE }
                            )
                            RadioButtonWithLabel(
                                selected = mapType.value == AMap.MAP_TYPE_NAVI,
                                label = "导航地图",
                                onSelected = { mapType.value = AMap.MAP_TYPE_NAVI }
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "路况图层",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = showTraffic.value,
                                    onCheckedChange = { showTraffic.value = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RadioButtonWithLabel(
    selected: Boolean,
    label: String,
    onSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp)
            .clickable { onSelected() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelected
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun MainScreen(
    currentLocation: AMapLocation?,
    locationStatus: String,
    locationError: String?
) {
    Scaffold(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "轨迹大师",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = locationStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                locationStatus.contains("成功") -> MaterialTheme.colorScheme.primary
                                locationStatus.contains("失败") || locationStatus.contains("拒绝") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (locationError != null) {
                        Text(
                            text = locationError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                        )
                    }

                    if (currentLocation != null && currentLocation.errorCode == 0) {
                        Text(
                            text = "经纬度: ${String.format("%.6f", currentLocation.longitude)}, ${String.format("%.6f", currentLocation.latitude)} | 精度: ${currentLocation.accuracy.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AMapView(currentLocation = currentLocation)
                }
            }
        }
    }
}