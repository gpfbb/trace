package com.trackmaster.app.core

import android.util.Log
import com.trackmaster.app.storage.Repository
import com.trackmaster.app.throttle.SmartScheduler
import com.trackmaster.app.throttle.ThrottleConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * 围栏追踪引擎 — 核心状态机
 *
 * 核心规则：
 * 1. 叶子优先：仅追踪深度最大的命中围栏，父级不参与实时计时
 * 2. 漂移过滤：accuracy > threshold 的定位点被丢弃
 * 3. 智能降频：通过 SmartScheduler 自适应定位频率
 * 4. 所有计算使用事件 timestamp，不用 System.currentTimeMillis()
 */
class Engine(
    fences: List<Geofence>,
    private val repo: Repository,
    throttleConfig: ThrottleConfig = ThrottleConfig(),
) {
    companion object { private const val TAG = "Engine" }

    private val scheduler = SmartScheduler(fences, throttleConfig)
    private val fenceMap: Map<String, Geofence>
    private val leafFences: List<Geofence>

    /** 当前命中的叶子围栏 ID */
    @Volatile var currentLeafId: String? = null
        private set

    /** 当前活跃 Segment 的进入时间戳（事件时间） */
    @Volatile var currentEnterTs = 0L
        private set

    private val segCounter = AtomicInteger(0)

    init {
        val tree = buildFenceTree(fences)
        fenceMap = tree.fenceMap
        leafFences = tree.leavesByDepth
    }

    // ============================================================
    // 生命周期
    // ============================================================

    /** 初始化：恢复未关闭的 Segment */
    suspend fun init() {
        val open = repo.getOpenSegments()
        if (open.isNotEmpty()) {
            val latest = open.last()
            currentLeafId = latest.leafGeofenceId
            currentEnterTs = latest.startTs
            Log.d(TAG, "恢复未关闭 Segment: ${latest.leafGeofenceId}")
        }
    }

    // ============================================================
    // 定位处理（核心入口）
    // ============================================================

    /**
     * 接收位置更新
     *
     * 流程：
     * 1. SmartScheduler 评估是否应处理
     * 2. 漂移过滤（双重保险）
     * 3. BBox 快速排除 → 精确命中叶子围栏
     * 4. 状态转移 → 生成 StaySegment
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
        if (fix.accuracy > 50f) return null

        // 3. 查找最深命中的叶子围栏
        val matched = findDeepestMatch(fix)

        // 4. 状态转移
        if (matched?.id != currentLeafId) {
            return transition(matched?.id, fix.timestamp)
        }
        return null
    }

    /** 活动识别回调（转发给 Scheduler） */
    fun onActivityDetected(activity: ActivityResult) {
        scheduler.onActivityDetected(activity)
    }

    /** 屏幕状态变更（转发给 Scheduler） */
    fun onScreenStateChanged(isOn: Boolean) {
        scheduler.onScreenStateChanged(isOn)
    }

    /** SLC 系统回调（转发给 Scheduler） */
    fun onSignificantLocationChange(distanceM: Double) {
        scheduler.onSignificantLocationChange(distanceM)
    }

    /** 设置电源模式 */
    fun setPowerMode(mode: PowerMode) {
        scheduler.setMode(mode)
    }

    fun shouldRegisterSLC(): Boolean = scheduler.shouldRegisterSLC()
    fun shouldUnregisterSLC(): Boolean = scheduler.shouldUnregisterSLC()

    /** 应用终止 */
    suspend fun onAppTerminate() {
        if (currentLeafId != null) {
            transition(null, System.currentTimeMillis())
        }
        repo.flush()
    }

    /** 资源释放 */
    suspend fun dispose() {
        onAppTerminate()
        scheduler.dispose()
        repo.dispose()
    }

    // ============================================================
    // 查询
    // ============================================================

    fun getCurrentDurationSec(): Long {
        val enterTs = currentEnterTs
        if (currentLeafId == null || enterTs == 0L) return 0
        return (System.currentTimeMillis() - enterTs) / 1000
    }

    fun getScheduler(): SmartScheduler = scheduler

    // ============================================================
    // 内部
    // ============================================================

    /** BBox 快速排除 → 精确 point-in-polygon，返回最深叶子 */
    private fun findDeepestMatch(fix: LocationFix): Geofence? {
        var deepest: Geofence? = null
        var maxDepth = -1

        for (leaf in leafFences) {
            val bbox = leaf.bbox ?: continue
            if (!GeoMath.pointInBBox(fix.lat, fix.lng, bbox)) continue
            if (GeoMath.pointInFence(GeoPoint(fix.lat, fix.lng), leaf)) {
                if (leaf.depth > maxDepth) {
                    maxDepth = leaf.depth
                    deepest = leaf
                }
            }
        }
        return deepest
    }

    /**
     * 状态转移：关闭旧 Segment → 打开新 Segment
     */
    private suspend fun transition(newLeafId: String?, ts: Long): StaySegment? {
        var closedSeg: StaySegment? = null

        // 关闭旧的
        val oldLeafId = currentLeafId
        val oldEnterTs = currentEnterTs
        if (oldLeafId != null && oldEnterTs > 0) {
            val enterDateKey = formatDateKey(oldEnterTs)
            val exitDateKey = formatDateKey(ts)

            closedSeg = StaySegment(
                id = "seg_${segCounter.incrementAndGet()}_$oldEnterTs",
                leafGeofenceId = oldLeafId,
                startTs = oldEnterTs,
                endTs = ts,
                status = if (enterDateKey != exitDateKey) SegmentStatus.CROSSED_DAY else SegmentStatus.COMPLETED,
            )

            try {
                repo.enqueueSegment(closedSeg)
            } catch (e: Exception) {
                Log.e(TAG, "Segment 写入错误", e)
            }
        }

        // 打开新的
        currentLeafId = newLeafId
        currentEnterTs = if (newLeafId != null) ts else 0L

        return closedSeg
    }

    private fun formatDateKey(ts: Long): String = Repository.formatDateKey(ts)
}
