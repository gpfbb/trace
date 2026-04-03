package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
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
                    locationError = locationError.value,
                    onRequestPermission = { requestLocationPermission() }
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
fun MainScreen(
    currentLocation: AMapLocation?,
    locationStatus: String,
    locationError: String?,
    onRequestPermission: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "轨迹大师",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "状态: $locationStatus",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (locationError != null) {
                            Text(
                                text = "❌ $locationError",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (currentLocation != null && currentLocation.errorCode == 0) {
                            Text("✅ 经度: ${currentLocation.longitude}")
                            Text("✅ 纬度: ${currentLocation.latitude}")
                            Text("✅ 精度: ${currentLocation.accuracy}米")
                            currentLocation.address?.let { 
                                Text("📍 地址: $it")
                            }
                        } else {
                            Text("等待位置数据...")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重新请求定位权限 / 重启定位")
                }
            }
        }
    }
}
