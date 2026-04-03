package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.MapView
import com.amap.api.maps.model.MyLocationStyle
import com.trackmaster.app.service.LocationService
import com.trackmaster.app.service.MotionState
import com.trackmaster.app.service.PowerMode
import com.trackmaster.app.ui.theme.TrackMasterTheme
import com.trackmaster.app.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var locationService: LocationService

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.checkPermissions()
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            requestBackgroundLocationPermission()
        }
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.checkPermissions()
        if (granted) {
            requestNotificationPermission()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        locationService.init()
        
        setContent {
            TrackMasterTheme(darkTheme = viewModel.uiState.value.isDarkMode) {
                MainScreen(viewModel = viewModel, locationService = locationService)
            }
        }
        
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()
        
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            requestBackgroundLocationPermission()
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                requestNotificationPermission()
            }
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, locationService: LocationService) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            locationService.startLocation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (uiState.isDarkMode) {
                        Modifier.background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF0D1B2A),
                                    Color(0xFF1B263B),
                                    Color(0xFF0D1B2A)
                                )
                            )
                        )
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    }
                )
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        try {
                            onCreate(Bundle())
                            mapView = this
                            val aMap = this.map
                            aMap.uiSettings.isMyLocationButtonEnabled = false
                            aMap.isMyLocationEnabled = true
                            
                            val myLocationStyle = MyLocationStyle()
                            myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
                            myLocationStyle.interval(2000)
                            aMap.setMyLocationStyle(myLocationStyle)
                            
                            onResume()
                            isMapReady = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (!isMapReady) {
                        try {
                            view.onResume()
                            isMapReady = true
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                InfoCard(
                    uiState = uiState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                ControlDrawer(
                    uiState = uiState,
                    onStartTracking = { 
                        try {
                            if (viewModel.uiState.value.hasLocationPermission) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                                    !viewModel.uiState.value.hasBackgroundLocationPermission) {
                                    viewModel.openAppSettings()
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && 
                                    !viewModel.uiState.value.hasNotificationPermission) {
                                    viewModel.openAppSettings()
                                } else {
                                    viewModel.startTracking()
                                }
                            } else {
                                viewModel.openAppSettings()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onPauseTracking = { 
                        try {
                            viewModel.pauseTracking()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onResumeTracking = { 
                        try {
                            viewModel.resumeTracking()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onStopTracking = { 
                        try {
                            viewModel.stopTracking()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onPowerModeChange = { viewModel.setPowerMode(it) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                mapView?.onPause()
                mapView?.onDestroy()
                locationService.stopLocation()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun InfoCard(
    uiState: com.trackmaster.app.viewmodel.UiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.isDarkMode) 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f) 
            else 
                MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoItem(
                    label = "状态",
                    value = when {
                        uiState.isPaused -> "已暂停"
                        uiState.isTracking -> "记录中"
                        else -> "待机"
                    }
                )
                InfoItem(
                    label = "运动",
                    value = when (uiState.motionState) {
                        MotionState.STATIONARY -> "静止"
                        MotionState.WALKING -> "步行"
                        MotionState.RIDING -> "骑行"
                        MotionState.DRIVING -> "驾车"
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoItem(
                    label = "速度",
                    value = "${(uiState.currentSpeed * 3.6).toInt()} km/h"
                )
                InfoItem(
                    label = "精度",
                    value = "${uiState.accuracy.toInt()} m"
                )
            }
        }
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ControlDrawer(
    uiState: com.trackmaster.app.viewmodel.UiState,
    onStartTracking: () -> Unit,
    onPauseTracking: () -> Unit,
    onResumeTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onPowerModeChange: (PowerMode) -> Unit,
    onToggleDarkMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.isDarkMode) 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f) 
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!uiState.isTracking) {
                Button(
                    onClick = onStartTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = "开始记录",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (uiState.isPaused) {
                        Button(
                            onClick = onResumeTracking,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("继续", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Button(
                            onClick = onPauseTracking,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("暂停", color = MaterialTheme.colorScheme.onSecondary)
                        }
                    }

                    Button(
                        onClick = onStopTracking,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("停止", color = MaterialTheme.colorScheme.onError)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    text = "省电",
                    isSelected = uiState.powerMode == PowerMode.POWER_SAVE,
                    onClick = { onPowerModeChange(PowerMode.POWER_SAVE) }
                )
                ActionButton(
                    text = "均衡",
                    isSelected = uiState.powerMode == PowerMode.BALANCED,
                    onClick = { onPowerModeChange(PowerMode.BALANCED) }
                )
                ActionButton(
                    text = "性能",
                    isSelected = uiState.powerMode == PowerMode.HIGH_PERFORMANCE,
                    onClick = { onPowerModeChange(PowerMode.HIGH_PERFORMANCE) }
                )
                ActionButton(
                    text = "夜间",
                    isSelected = uiState.isDarkMode,
                    onClick = onToggleDarkMode
                )
            }
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(70.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary 
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
