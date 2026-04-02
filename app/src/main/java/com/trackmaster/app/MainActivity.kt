package com.trackmaster.app

import android.Manifest
import android.content.pm.PackageManager
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
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.model.MyLocationStyle
import com.trackmaster.app.service.MotionState
import com.trackmaster.app.service.PowerMode
import com.trackmaster.app.ui.theme.TrackMasterTheme
import com.trackmaster.app.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.checkPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrackMasterTheme(darkTheme = viewModel.uiState.value.isDarkMode) {
                MainScreen(viewModel = viewModel)
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }

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
                        Modifier.background(Color(0xFFF5F7FA))
                    }
                )
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        onCreate(Bundle())
                        mapView = this
                        val aMap = this.map
                        aMap.uiSettings.isMyLocationButtonEnabled = false
                        aMap.isMyLocationEnabled = true
                        
                        val myLocationStyle = MyLocationStyle()
                        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
                        myLocationStyle.interval(2000)
                        aMap.setMyLocationStyle(myLocationStyle)
                    }
                },
                modifier = Modifier.fillMaxSize()
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
                    onStartTracking = { viewModel.startTracking() },
                    onPauseTracking = { viewModel.pauseTracking() },
                    onResumeTracking = { viewModel.resumeTracking() },
                    onStopTracking = { viewModel.stopTracking() },
                    onPowerModeChange = { viewModel.setPowerMode(it) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mapView?.onDestroy()
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
                Color(0xCC1B263B) else Color(0xEEFFFFFF)
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
                    },
                    isDark = uiState.isDarkMode
                )
                InfoItem(
                    label = "运动",
                    value = when (uiState.motionState) {
                        MotionState.STATIONARY -> "静止"
                        MotionState.WALKING -> "步行"
                        MotionState.RIDING -> "骑行"
                        MotionState.DRIVING -> "驾车"
                    },
                    isDark = uiState.isDarkMode
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoItem(
                    label = "速度",
                    value = "${(uiState.currentSpeed * 3.6).toInt()} km/h",
                    isDark = uiState.isDarkMode
                )
                InfoItem(
                    label = "精度",
                    value = "${uiState.accuracy.toInt()} m",
                    isDark = uiState.isDarkMode
                )
            }
        }
    }
}

@Composable
fun InfoItem(label: String, value: String, isDark: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) Color.White else Color(0xFF1F2937)
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
                Color(0xE61B263B) else Color(0xFFFFFFFF)
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
                    .background(if (uiState.isDarkMode) Color(0xFF4A5568) else Color(0xFFE5E7EB))
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!uiState.isTracking) {
                Button(
                    onClick = onStartTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(
                        text = "开始记录",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                        ) {
                            Text("继续", color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = onPauseTracking,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                        ) {
                            Text("暂停", color = Color.White)
                        }
                    }

                    Button(
                        onClick = onStopTracking,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                    ) {
                        Text("停止", color = Color.White)
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
                    isDark = uiState.isDarkMode,
                    onClick = { onPowerModeChange(PowerMode.POWER_SAVE) }
                )
                ActionButton(
                    text = "均衡",
                    isSelected = uiState.powerMode == PowerMode.BALANCED,
                    isDark = uiState.isDarkMode,
                    onClick = { onPowerModeChange(PowerMode.BALANCED) }
                )
                ActionButton(
                    text = "性能",
                    isSelected = uiState.powerMode == PowerMode.HIGH_PERFORMANCE,
                    isDark = uiState.isDarkMode,
                    onClick = { onPowerModeChange(PowerMode.HIGH_PERFORMANCE) }
                )
                ActionButton(
                    text = "夜间",
                    isSelected = uiState.isDarkMode,
                    isDark = uiState.isDarkMode,
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
    isDark: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(70.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) {
                if (isDark) Color(0xFF2196F3) else Color(0xFF2196F3)
            } else {
                if (isDark) Color(0xFF374151) else Color(0xFFF3F4F6)
            }
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected || !isDark) Color.White else Color(0xFF9CA3AF)
        )
    }
}
