package com.trackmaster.app.core

import android.util.Log
import com.trackmaster.app.storage.Repository
import com.trackmaster.app.utils.SmartScheduler
import com.trackmaster.app.utils.ThrottleConfig
import com.trackmaster.app.utils.GeoMath.FenceState
import com.trackmaster.app.utils.DynamicBufferManager
import com.trackmaster.app.utils.GeoMath
import com.trackmaster.app.utils.buildFenceTree
import com.trackmaster.app.core.GeoPoint
import java.util.concurrent.atomic.AtomicInteger

/**
 * 围栏追踪引擎 — 核心状态机
 *
 * 核心规则：
 * 1. 叶子优先：仅追踪深度最大的命中围栏，父级不参与实时计时
 * 2. 嵌套识别：同时识别并返回所有命中的围栏（包括父级和叶子）
 * 3. 漂移过滤：accuracy > threshold 的定位点被丢弃
 * 4. 智能降频：通过 SmartScheduler 自适应定位频率
 * 5. 所有计算使用事件 timestamp，不用 System.currentTimeMillis()
 */
class Engine(
    fences: List<Geofence>,
    private val repo: Repository,
    throttleConfig: ThrottleConfig = ThrottleConfig(),
    private val useSpatialIndex: Boolean = true,
    private val useDynamicBuffer: Boolean = true,
) {
    companion object { private const val TAG = "Engine" }

    private val scheduler = SmartScheduler(fences, throttleConfig)
    private val fenceMap: Map<String, Geofence>
    private val leafFences: List<Geofence>
    private val allFences: List<Geofence>
    private val spatialIndex: SpatialIndex?
    private var queryCount: Int = 0
    private val bufferManager: DynamicBufferManager?
    private val fencePreviousStates = mutableMapOf<String, FenceState>()

    /** 位置处理统计回调 */
    var onLocationProcessed: ((Boolean) -> Unit)? = null

    /** 上一次评估的 shouldProcess 结果，供外部查询 */
    @Volatile var lastShouldProcess: Boolean = false
        private set

    /** 当前命中的叶子围栏 ID */
    @Volatile var currentLeafId: String? = null
        private set

    /** 当前所有命中的围栏 ID 列表（按深度排序，最深在前） */
    @Volatile var currentMatchedFenceIds: List<String> = emptyList()
        private set

    /** 设置当前匹配的围栏ID列表（用于外部同步更新） */
    fun setCurrentMatchedFenceIds(ids: List<String>) {
        currentMatchedFenceIds = ids
    }

    /** 当前活跃 Segment 的进入时间戳（事件时间） */
    @Volatile var currentEnterTs = 0L
        private set

    /** 当前电源模式 */
    @Volatile var currentPowerMode: PowerMode = PowerMode.BALANCED
        private set

    private val segCounter = AtomicInteger(0)

    init {
        val tree = buildFenceTree(fences)
        fenceMap = tree.fenceMap
        leafFences = tree.leavesByDepth
        allFences = fences

        // 初始化空间索引（如果启用）
        spatialIndex = if (useSpatialIndex && fences.isNotEmpty()) {
            val index = GridSpatialIndex()
            index.build(fences)
            Log.d(TAG, "空间索引已构建: ${fences.size} 个围栏")
            index
        } else {
            Log.d(TAG, "空间索引未启用或围栏为空")
            null
        }

        // 初始化动态缓冲区管理器（如果启用）
        bufferManager = if (useDynamicBuffer) {
            val manager = DynamicBufferManager()
            Log.d(TAG, "动态缓冲区管理器已初始化")
            manager
        } else {
            Log.d(TAG, "动态缓冲区管理器未启用")
            null
        }
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
     * 2. 漂移过滤
     * 3. BBox 快速排除 → 精确命中所有围栏（包括嵌套的父级和子级）
     * 4. 更新当前命中的所有围栏列表
     * 5. 状态转移 → 生成 StaySegment
     *
     * @return Pair(生成的 StaySegment（如有）, 是否应该处理定位)
     */
    suspend fun onLocationUpdate(
        fix: LocationFix,
        accel: AccelerometerData? = null,
    ): Pair<StaySegment?, Boolean> {
        // 1. SmartScheduler 评估
        val decision = scheduler.evaluate(fix, accel)
        // 记录结果供外部查询
        lastShouldProcess = decision.shouldProcess
        // 统计回调
        onLocationProcessed?.invoke(decision.shouldProcess)
        if (!decision.shouldProcess) return Pair(null, false)

        // 2. 漂移过滤（与SmartScheduler保持一致）
        if (fix.accuracy.toDouble() > scheduler.config.driftThresholdM) return Pair(null, false)

        // 3. 获取动态缓冲区大小
        var currentBufferSize = 0.0
        if (bufferManager != null) {
            val speedMps = fix.speed?.toDouble()
            val bufferState = bufferManager.update(
                accuracyM = fix.accuracy.toDouble(),
                speedMps = speedMps,
                powerMode = currentPowerMode
            )
            currentBufferSize = bufferState.bufferSizeM
            Log.d(TAG, "动态缓冲区: ${"%.1f".format(currentBufferSize)}m")
        }

        // 4. 查找所有命中的围栏（包括嵌套的父级和子级）
        val allMatched = findAllMatchedFences(fix, currentBufferSize, true)

        // 5. 更新当前命中的所有围栏列表（按深度降序排列）
        currentMatchedFenceIds = allMatched
            .sortedByDescending { it.depth }
            .map { it.id }

        // 6. 找出最深的叶子围栏用于计时
        val deepest = allMatched.maxByOrNull { it.depth }

        // 7. 状态转移
        if (deepest?.id != currentLeafId) {
            return Pair(transition(deepest?.id, fix.timestamp), true)
        }
        return Pair(null, true)
    }

    /**
     * 仅评估位置是否应该处理（用于省电控制）
     * 不执行完整的围栏检测逻辑，返回是否应该处理
     */
    fun evaluateLocation(fix: LocationFix): Boolean {
        val decision = scheduler.evaluate(fix, null)
        lastShouldProcess = decision.shouldProcess
        return decision.shouldProcess
    }

    /**
     * 同步检查当前位置是否在任何围栏内（用于省电决策）
     * 不更新状态，不触发事件，仅查询
     */
    fun isLocationInAnyFence(fix: LocationFix): Boolean {
        val matchedIds = checkFenceMatch(fix)
        return matchedIds.isNotEmpty()
    }

    /** 活动识别回调（转发给 Scheduler） */
    fun onActivityDetected(activity: ActivityResult) {
        scheduler.onActivityDetected(activity)
    }

    /** SLC 系统回调（转发给 Scheduler） */
    fun onSignificantLocationChange(distanceM: Double) {
        scheduler.onSignificantLocationChange(distanceM)
    }

    /** 设置电源模式 */
    fun setPowerMode(mode: PowerMode) {
        scheduler.setMode(mode)
        currentPowerMode = mode
        Log.d(TAG, "电源模式已设置为: $mode")
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

    /** 设置当前围栏（用于恢复状态时直接设置） */
    fun setCurrentFenceForRecovery(fenceId: String?, enterTs: Long) {
        currentLeafId = fenceId
        currentEnterTs = enterTs
        Log.d(TAG, "Engine恢复状态: currentLeafId=$fenceId, enterTs=$enterTs")
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

    /**
     * BBox 快速排除 → 精确 point-in-polygon，返回所有命中的围栏
     * 包括嵌套的父级和子级围栏，按面积升序排列（面积最小的在前）
     *
     * @param bufferM 缓冲区大小（米），0表示不使用缓冲区
     * @param considerBufferAsInside 是否将缓冲区视为"内部"
     */
    private fun findAllMatchedFences(
        fix: LocationFix,
        bufferM: Double = 0.0,
        considerBufferAsInside: Boolean = false
    ): List<Geofence> {
        val matched = mutableListOf<Geofence>()
        val point = GeoPoint(fix.lat, fix.lng)

        val startTime = System.currentTimeMillis()
        val candidateCount: Int

        if (spatialIndex != null) {
            // 使用空间索引获取候选围栏
            val candidateIds = spatialIndex.query(point)
            candidateCount = candidateIds.size

            for (fenceId in candidateIds) {
                val fence = fenceMap[fenceId] ?: continue
                val bbox = fence.bbox ?: continue
                if (!GeoMath.pointInBBox(fix.lat, fix.lng, bbox)) continue
                if (GeoMath.pointInFence(point, fence, bufferM, considerBufferAsInside)) {
                    matched.add(fence)
                }
            }
        } else {
            // 回退：遍历所有围栏
            candidateCount = allFences.size

            for (fence in allFences) {
                val bbox = fence.bbox ?: continue
                if (!GeoMath.pointInBBox(fix.lat, fix.lng, bbox)) continue
                if (GeoMath.pointInFence(point, fence, bufferM, considerBufferAsInside)) {
                    matched.add(fence)
                }
            }
        }

        val queryTime = System.currentTimeMillis() - startTime

        // 每100次查询记录一次性能统计
        if (queryCount % 100 == 0) {
            val hitCount = matched.size
            Log.d(TAG, "空间查询统计: 候选数=$candidateCount, 命中数=$hitCount, 耗时=${queryTime}ms, " +
                    "索引=${if (spatialIndex != null) "启用" else "禁用"}")
        }
        queryCount++

        // 按深度降序排列返回（深度最大的叶子围栏在前）
        // 注意：Engine.kt调用者已按depth排序，此处保持一致性
        return matched.sortedByDescending { it.depth }
    }

    /**
     * 检查位置是否在围栏内（不更新状态）
     *
     * 用于应用启动时的状态恢复，检查用户当前位置是否在持久化的围栏内
     * 注意：状态恢复使用精确检测（无缓冲区），避免错误恢复
     *
     * @param fix 位置信息
     * @return 命中的围栏 ID 列表（按深度降序排列，最深在前）
     */
    fun checkFenceMatch(fix: LocationFix): List<String> {
        val matched = findAllMatchedFences(fix, bufferM = 10.0, considerBufferAsInside = true)
        return matched.map { it.id }
    }

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

            runCatching { repo.enqueueSegment(closedSeg) }
                .onFailure { Log.e(TAG, "Segment 写入错误", it) }
        }

        // 打开新的
        currentLeafId = newLeafId
        currentEnterTs = if (newLeafId != null) ts else 0L

        return closedSeg
    }

    private fun formatDateKey(ts: Long): String = Repository.formatDateKey(ts)

    /**
     * 带位置验证的状态恢复结果
     */
    data class RestoreResult(
        /** 是否需要恢复围栏计时（用户仍在围栏内） */
        val shouldRestore: Boolean,
        /** 需要恢复的围栏ID（如果shouldRestore=true） */
        val fenceId: String? = null,
        /** 进入时间戳（如果shouldRestore=true） */
        val enterTs: Long = 0L,
        /** 关闭的Segment（如果用户已经离开） */
        val closedSegment: StaySegment? = null,
        /** 真空期时长（ms） */
        val vacuumDurationMs: Long = 0L,
    )

    /**
     * 带位置验证的状态恢复
     *
     * 应用重启后首次获取定位时调用，用于验证用户是否仍在之前的围栏内
     * 处理真空期：如果用户已经离开，则关闭之前的Segment
     *
     * @param fix 首次定位信息
     * @param vacuumStartTs 真空期开始时间戳（应用被关闭的时间）
     * @return 恢复结果
     */
    suspend fun initWithLocation(fix: LocationFix, vacuumStartTs: Long? = null): RestoreResult {
        val open = repo.getOpenSegments()
        if (open.isEmpty()) {
            Log.d(TAG, "没有未关闭的Segment需要恢复")
            return RestoreResult(shouldRestore = false)
        }

        val latest = open.last()
        Log.d(TAG, "恢复未关闭Segment: ${latest.leafGeofenceId}, 进入时间=${latest.startTs}")

        // 检查当前位置是否还在该围栏内
        val matchedFenceIds = checkFenceMatch(fix)
        val isStillInFence = matchedFenceIds.contains(latest.leafGeofenceId)

        // 计算真空期时长
        val vacuumDuration = vacuumStartTs?.let { fix.timestamp - it } ?: 0L

        if (isStillInFence) {
            // 用户仍在围栏内，继续计时
            currentLeafId = latest.leafGeofenceId
            currentEnterTs = latest.startTs
            Log.d(TAG, "用户仍在围栏 ${latest.leafGeofenceId} 内，继续计时")

            // 如果有真空期，记录日志但不调整时间
            if (vacuumDuration > 0) {
                Log.d(TAG, "真空期时长: ${vacuumDuration}ms (不计算)")
            }

            return RestoreResult(
                shouldRestore = true,
                fenceId = latest.leafGeofenceId,
                enterTs = latest.startTs,
                closedSegment = null,
                vacuumDurationMs = vacuumDuration
            )
        } else {
            // 用户已经离开围栏，关闭Segment
            Log.d(TAG, "用户已离开围栏 ${latest.leafGeofenceId}，关闭Segment")

            // 使用真空期开始时间或当前位置时间作为离开时间
            val exitTs = vacuumStartTs ?: fix.timestamp

            val closedSeg = StaySegment(
                id = "seg_${segCounter.incrementAndGet()}_${latest.startTs}",
                leafGeofenceId = latest.leafGeofenceId,
                startTs = latest.startTs,
                endTs = exitTs,
                status = SegmentStatus.COMPLETED,
            )

            runCatching { repo.enqueueSegment(closedSeg) }
                .onFailure { Log.e(TAG, "真空期Segment关闭错误", it) }
            Log.d(TAG, "真空期关闭Segment: ${latest.leafGeofenceId}, 离开时间=$exitTs")

            // 清除当前状态
            currentLeafId = null
            currentEnterTs = 0L
            currentMatchedFenceIds = emptyList()

            return RestoreResult(
                shouldRestore = false,
                fenceId = null,
                enterTs = 0L,
                closedSegment = closedSeg,
                vacuumDurationMs = vacuumDuration
            )
        }
    }
}
