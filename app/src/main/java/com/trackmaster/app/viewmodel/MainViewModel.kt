package com.trackmaster.app.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.service.LocationService
import com.trackmaster.app.service.MotionState
import com.trackmaster.app.service.PowerMode
import com.trackmaster.app.service.TrackingService
import com.trackmaster.app.data.repository.TrackingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiState(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val currentSpeed: Float = 0f,
    val totalDistance: Float = 0f,
    val accuracy: Float = 0f,
    val motionState: MotionState = MotionState.STATIONARY,
    val powerMode: PowerMode = PowerMode.BALANCED,
    val hasLocationPermission: Boolean = false,
    val hasBackgroundLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isDarkMode: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationService: LocationService,
    private val repository: TrackingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            locationService.locationData.collect { data ->
                data?.let {
                    _uiState.update { state ->
                        state.copy(
                            currentSpeed = data.location?.speed ?: 0f,
                            accuracy = data.location?.accuracy ?: 0f,
                            motionState = data.motionState
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            locationService.powerMode.collect { mode ->
                _uiState.update { it.copy(powerMode = mode) }
            }
        }

        checkPermissions()
    }

    fun checkPermissions() {
        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasLocation
        }

        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        _uiState.update {
            it.copy(
                hasLocationPermission = hasLocation,
                hasBackgroundLocationPermission = hasBackgroundLocation,
                hasNotificationPermission = hasNotification
            )
        }
    }

    fun startTracking() {
        TrackingService.startService(context)
        _uiState.update { it.copy(isTracking = true, isPaused = false) }
    }

    fun pauseTracking() {
        TrackingService.pauseService(context)
        _uiState.update { it.copy(isPaused = true) }
    }

    fun resumeTracking() {
        TrackingService.resumeService(context)
        _uiState.update { it.copy(isPaused = false) }
    }

    fun stopTracking() {
        TrackingService.stopService(context)
        _uiState.update { it.copy(isTracking = false, isPaused = false) }
    }

    fun setPowerMode(mode: PowerMode) {
        locationService.setPowerMode(mode)
    }

    fun toggleDarkMode() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
