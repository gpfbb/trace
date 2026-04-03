package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.MyLocationStyle
import com.trackmaster.app.ui.theme.TrackMasterTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity(), AMapLocationListener {

    private val viewModel: MainViewModel by viewModels()
    
    private var locationClient: AMapLocationClient? = null
    private var mapView: MapView? = null
    private var currentLocation: AMapLocation? by mutableStateOf(null)
    
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                     permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            startLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val activityContext = this
        
        AMapLocationClient.updatePrivacyShow(activityContext, true, true)
        AMapLocationClient.updatePrivacyAgree(activityContext, true)
        AMapLocationClient.setApiKey(BuildConfig.AMAP_API_KEY)
        
        MapsInitializer.updatePrivacyShow(activityContext, true, true)
        MapsInitializer.updatePrivacyAgree(activityContext, true)
        
        initLocationClient()
        
        setContent {
            TrackMasterTheme {
                MainScreen(
                    mapView = { mapView },
                    currentLocation = currentLocation,
                    onRequestPermission = { requestLocationPermission() },
                    onStartLocation = { startLocation() }
                )
            }
        }
        
        requestLocationPermission()
    }
    
    private fun initLocationClient() {
        locationClient = AMapLocationClient(this)
        locationClient?.setLocationListener(this)
        
        val option = AMapLocationClientOption()
        option.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        option.interval = 2000
        option.isNeedAddress = true
        option.isOnceLocation = false
        locationClient?.setLocationOption(option)
    }
    
    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startLocation()
        }
    }
    
    private fun startLocation() {
        locationClient?.startLocation()
    }
    
    override fun onLocationChanged(location: AMapLocation?) {
        currentLocation = location
    }
    
    override fun onDestroy() {
        super.onDestroy()
        locationClient?.onDestroy()
        mapView?.onDestroy()
    }
    
    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }
}

@Composable
fun MainScreen(
    mapView: () -> MapView?,
    currentLocation: AMapLocation?,
    onRequestPermission: () -> Unit,
    onStartLocation: () -> Unit
) {
    var isMapReady by remember { mutableStateOf(false) }
    var localMapView by remember { mutableStateOf<MapView?>(null) }
    
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { context ->
                    MapView(context).apply {
                        onCreate(Bundle())
                        localMapView = this
                        val aMap = this.map
                        aMap.uiSettings.isMyLocationButtonEnabled = true
                        aMap.isMyLocationEnabled = true
                        
                        val myLocationStyle = MyLocationStyle()
                        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
                        myLocationStyle.interval(2000)
                        aMap.setMyLocationStyle(myLocationStyle)
                        
                        onResume()
                        isMapReady = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (!isMapReady) {
                        view.onResume()
                        isMapReady = true
                    }
                }
            )
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "位置信息",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        if (currentLocation != null) {
                            Text("经度: ${currentLocation!!.longitude}")
                            Text("纬度: ${currentLocation!!.latitude}")
                            Text("精度: ${currentLocation!!.accuracy}米")
                            currentLocation!!.address?.let { 
                                Text("地址: $it")
                            }
                        } else {
                            Text("正在获取位置...")
                        }
                    }
                }
            }
        }
    }
}
