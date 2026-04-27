package com.trackmaster.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.trackmaster.app.utils.AppLog
import androidx.core.content.ContextCompat

class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
    }

    data class PermissionStatus(
        val hasLocationPermission: Boolean = false,
        val hasBackgroundLocationPermission: Boolean = false,
        val isIgnoringBatteryOptimizations: Boolean = false,
        val hasAllPermissions: Boolean = false
    )

    fun checkPermissions(): PermissionStatus {
        val hasFineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarseLocation = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val hasLocationPermission = hasFineLocation || hasCoarseLocation

        val hasBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            hasLocationPermission
        }

        val isIgnoringBattery = isIgnoringBatteryOptimizations()

        val hasAll = hasLocationPermission && hasBackgroundLocation && isIgnoringBattery

        return PermissionStatus(
            hasLocationPermission = hasLocationPermission,
            hasBackgroundLocationPermission = hasBackgroundLocation,
            isIgnoringBatteryOptimizations = isIgnoringBattery,
            hasAllPermissions = hasAll
        )
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    fun getBatteryOptimizationIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                intent
            } catch (e: Exception) {
                AppLog.e(TAG, "无法创建电池优化设置Intent", e)
                null
            }
        } else {
            null
        }
    }

    fun getAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun getLocationPermissionIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun getBackgroundLocationPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        } else {
            emptyArray()
        }
    }

    fun getLocationPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
