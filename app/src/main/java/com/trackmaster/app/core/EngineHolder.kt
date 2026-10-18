package com.trackmaster.app.core

import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.throttle.ThrottleConfig
import com.trackmaster.app.storage.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Engine 持有者
 * 通过 Koin 注入，不直接访问 Application 单例
 * 支持刷新围栏数据
 */
class EngineHolder(
    private val repository: Repository,
    private var throttleConfig: ThrottleConfig,
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
        // 【P1修复】替换 Engine 前先快照旧 Engine 的活动围栏状态。
        // 旧逻辑重建后 currentLeafId/currentEnterTs 全部丢失，用户"在围栏内编辑围栏"
        // 之后要等下一次 GPS 才恢复；若在这期间离开，新 Engine 认为本来就不在围栏内，
        // 不会生成 segment，这段停留彻底丢失。
        val oldEngine = _engine
        val oldLeafId = oldEngine?.currentLeafId
        val oldEnterTs = oldEngine?.currentEnterTs ?: 0L

        _engine = Engine(fences, repository, throttleConfig).apply {
            onLocationProcessed = { shouldProcess, _ ->
                appPreferences.incrementLocations(shouldProcess)
            }
            // 只有新围栏集合里仍存在该围栏时才迁移：围栏被删除/重建 ID 变化时，
            // 继续给一个不存在的围栏计时会让后续离开时的 segment 指向无效围栏。
            if (oldLeafId != null && oldEnterTs > 0 &&
                fences.any { it.id == oldLeafId }
            ) {
                setCurrentFenceForRecovery(oldLeafId, oldEnterTs)
                AppLog.d(TAG, "迁移活动围栏状态: $oldLeafId, enterTs=$oldEnterTs")
            }
        }
        if (oldEngine != null) {
            // SupervisorJob：dispose 里的异常不应影响其他协程
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                // closeActiveSegment=false：活动状态已迁移到新 Engine，
                // 旧 Engine 不能再 transition(null) 提前关闭这段停留
                runCatching { oldEngine.dispose(closeActiveSegment = false) }
                    .onFailure { AppLog.e(TAG, "旧Engine dispose失败", it) }
            }
        }
        AppLog.d(TAG, "Engine refreshed with ${fences.size} fences")
    }

    /**
     * 更新节流配置。
     *
     * 同时更新传给后续 Engine 的配置：EngineHolder 构造时保存的 throttleConfig
     * 是 refresh() 重建 Engine 的唯一来源，只改当前 scheduler 的话，
     * 用户改完设置后任意一次围栏刷新都会让配置退回旧值。
     */
    fun updateThrottleConfig(newConfig: ThrottleConfig) {
        throttleConfig = newConfig
        _engine?.getScheduler()?.updateConfig(newConfig)
    }

    companion object {
        private const val TAG = "EngineHolder"
    }
}
