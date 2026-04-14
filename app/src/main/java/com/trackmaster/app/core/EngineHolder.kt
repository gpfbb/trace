package com.trackmaster.app.core

import android.util.Log
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.utils.ThrottleConfig
import com.trackmaster.app.storage.Repository

/**
 * Engine 持有者
 * 通过 Koin 注入，不直接访问 Application 单例
 * 支持刷新围栏数据
 */
class EngineHolder(
    private val repository: Repository,
    private val throttleConfig: ThrottleConfig,
    private val appPreferences: AppPreferences
) {
    private var _engine: Engine? = null
    val engine: Engine get() = _engine ?: throw IllegalStateException("Engine not initialized. Call initialize() first.")

    val isInitialized: Boolean get() = _engine != null

    fun initialize(fences: List<Geofence>) {
        _engine = Engine(fences, repository, throttleConfig).apply {
            onLocationProcessed = { shouldProcess ->
                appPreferences.incrementLocations(shouldProcess)
            }
        }
        Log.d(TAG, "Engine initialized with ${fences.size} fences")
    }

    fun refresh(fences: List<Geofence>) {
        _engine = Engine(fences, repository, throttleConfig).apply {
            onLocationProcessed = { shouldProcess ->
                appPreferences.incrementLocations(shouldProcess)
            }
        }
        Log.d(TAG, "Engine refreshed with ${fences.size} fences")
    }

    companion object {
        private const val TAG = "EngineHolder"
    }
}
