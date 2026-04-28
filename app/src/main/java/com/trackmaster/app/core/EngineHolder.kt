package com.trackmaster.app.core

import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.throttle.ThrottleConfig
import com.trackmaster.app.storage.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            onLocationProcessed = { shouldProcess, _ ->
                appPreferences.incrementLocations(shouldProcess)
            }
        }
        AppLog.d(TAG, "Engine initialized with ${fences.size} fences")
    }

    fun refresh(fences: List<Geofence>) {
        // 【P0修复】异步dispose旧Engine避免资源泄漏
        // 旧Engine的scheduler、unflushed segments、active coroutines必须清理
        val oldEngine = _engine
        _engine = Engine(fences, repository, throttleConfig).apply {
            onLocationProcessed = { shouldProcess, _ ->
                appPreferences.incrementLocations(shouldProcess)
            }
        }
        if (oldEngine != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { oldEngine.dispose() }
                    .onFailure { AppLog.e(TAG, "旧Engine dispose失败", it) }
            }
        }
        AppLog.d(TAG, "Engine refreshed with ${fences.size} fences")
    }

    companion object {
        private const val TAG = "EngineHolder"
    }
}
