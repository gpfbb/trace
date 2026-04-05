package com.trackmaster.app.architecture.domain.usecases

import android.util.Log
import com.trackmaster.app.architecture.data.repositories.SegmentRepository
import com.trackmaster.app.architecture.domain.models.*
import com.trackmaster.app.architecture.common.utils.GeoMath
import com.trackmaster.app.architecture.common.utils.SmartScheduler
import com.trackmaster.app.architecture.common.utils.ThrottleConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * 围栏追踪引擎 — 核心状态机
 *
 * 核心规则：
 * 1. 面积优先：仅追踪面积最小的命中围栏，面积大的不参与实时计时
 * 2. 嵌套识别：同时识别并返回所有命中的围栏（包括父级和子级）
 * 3. 漂移过滤：accuracy > threshold 的定位点被丢弃
 * 4. 智能降频：通过 SmartScheduler 自适应定位频率
 * 5. 所有计算使用事件 timestamp，不用 System.currentTimeMillis()
 */
class FenceTrackingEngine(
    private val fences: List<Geofence>,
    private val segmentRepository: SegmentRepository,
    throttleConfig: ThrottleConfig = ThrottleConfig()
) {
    companion object {
        private const val TAG = "FenceTrackingEngine"
        private const val ACCURACY_THRESHOLD = 50f
    }

    private val scheduler = SmartScheduler(fences, throttleConfig)
    private val allFences: List<Geofence>

    /** 当前命中的围栏 ID（面积最小的） */
    @Volatile var currentFenceId: String? = null
        private set

    /** 当前所有命中的围栏 ID 列表（按面积升序排列，面积最小的在前） */
    @Volatile var currentMatchedFenceIds: List<String> = emptyList()
        private set

    /** 当前活跃 Segment 的进入时间戳（事件时间） */
    @Volatile var currentEnterTs = 0L
        private set

    private val segCounter = AtomicInteger(0)

    init {
        allFences = fences
        Log.d(TAG, "引擎初始化完成: ${fences.size} 个围栏")
    }

    /**
     * 初始化：恢复未关闭的 Segment
     */
    suspend fun initialize() {
        val open = segmentRepository.getOpenSegments()
        if (open.isNotEmpty()) {
            val latest = open.last()
            currentFenceId = latest.leafGeofenceId
            currentEnterTs = latest.startTs
            Log.d(TAG, "恢复未关闭 Segment: ${latest.leafGeofenceId}")
        }
    }

    /**
     * 接收位置更新
     *
     * 流程：
     * 1. SmartScheduler 评估是否应处理
     * 2. 漂移过滤（双重保险）
     * 3. BBox 快速排除 → 精确命中所有围栏（包括嵌套的父级和子级）
     * 4. 更新当前命中的所有围栏列表
     * 5. 状态转移 → 生成 StaySegment
     *
     * @return 生成的 StaySegment（如有），或 null
     */
    suspend fun onLocationUpdate(
        fix: LocationFix,
        accel: AccelerometerData? = null,
    ): StaySegment? {
        // 1. SmartScheduler 评估
        val decision = scheduler.evaluate(fix, accel)
        if (!decision.shouldProcess) return null

        // 2. 漂移过滤
        if (fix.accuracy > ACCURACY_THRESHOLD) return null

        // 3. 查找所有命中的围栏（包括嵌套的父级和子级）
        val allMatched = findAllMatchedFences(fix)
        
        // 4. 更新当前命中的所有围栏列表（按面积升序排列）
        currentMatchedFenceIds = allMatched.map { it.id }
        
        // 5. 找出面积最小的围栏用于计时
        val smallestFence = allMatched.firstOrNull()

        // 6. 状态转移
        if (smallestFence?.id != currentFenceId) {
            return transition(smallestFence?.id, fix.timestamp)
        }
        return null
    }

    /**
     * 活动识别回调（转发给 Scheduler）
     */
    fun onActivityDetected(activity: ActivityResult) {
        scheduler.onActivityDetected(activity)
    }

    /**
     * 屏幕状态变更（转发给 Scheduler）
     */
    fun onScreenStateChanged(isOn: Boolean) {
        scheduler.onScreenStateChanged(isOn)
    }

    /**
     * SLC 系统回调（转发给 Scheduler）
     */
    fun onSignificantLocationChange(distanceM: Double) {
        scheduler.onSignificantLocationChange(distanceM)
    }

    /**
     * 设置电源模式
     */
    fun setPowerMode(mode: PowerMode) {
        scheduler.setMode(mode)
    }

    /**
     * 应用终止
     */
    suspend fun onAppTerminate() {
        if (currentFenceId != null) {
            transition(null, System.currentTimeMillis())
        }
        segmentRepository.flush()
    }

    /**
     * 资源释放
     */
    suspend fun dispose() {
        onAppTerminate()
        scheduler.dispose()
    }

    /**
     * 获取当前停留时长（秒）
     */
    fun getCurrentDurationSec(): Long {
        val enterTs = currentEnterTs
        if (currentFenceId == null || enterTs == 0L) return 0
        return (System.currentTimeMillis() - enterTs) / 1000
    }

    /**
     * BBox 快速排除 → 精确 point-in-polygon，返回所有命中的围栏
     * 包括嵌套的父级和子级围栏，按面积升序排列（面积最小的在前）
     */
    private fun findAllMatchedFences(fix: LocationFix): List<Geofence> {
        val matched = mutableListOf<Geofence>()

        for (fence in allFences) {
            val bbox = fence.bbox ?: continue
            if (!GeoMath.pointInBBox(fix.lat, fix.lng, bbox)) continue
            if (GeoMath.pointInFence(GeoPoint(fix.lat, fix.lng), fence)) {
                matched.add(fence)
            }
        }
        
        // 按面积升序排列（面积最小的在前，优先识别小围栏）
        return matched.sortedBy { GeoMath.calculateFenceArea(it) }
    }

    /**
     * 状态转移：关闭旧 Segment → 打开新 Segment
     */
    private suspend fun transition(newFenceId: String?, ts: Long): StaySegment? {
        var closedSeg: StaySegment? = null

        // 关闭旧的
        val oldFenceId = currentFenceId
        val oldEnterTs = currentEnterTs
        if (oldFenceId != null && oldEnterTs > 0) {
            val enterDateKey = formatDateKey(oldEnterTs)
            val exitDateKey = formatDateKey(ts)

            closedSeg = StaySegment(
                id = "seg_${segCounter.incrementAndGet()}_$oldEnterTs",
                leafGeofenceId = oldFenceId,
                startTs = oldEnterTs,
                endTs = ts,
                status = if (enterDateKey != exitDateKey) SegmentStatus.CROSSED_DAY else SegmentStatus.COMPLETED,
            )

            try {
                segmentRepository.saveSegment(closedSeg)
            } catch (e: Exception) {
                Log.e(TAG, "Segment 写入错误", e)
            }
        }

        // 打开新的
        currentFenceId = newFenceId
        currentEnterTs = if (newFenceId != null) ts else 0L

        return closedSeg
    }

    private fun formatDateKey(ts: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = ts
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }
}