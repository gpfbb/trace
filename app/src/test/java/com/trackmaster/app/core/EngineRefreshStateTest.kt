package com.trackmaster.app.core

import android.content.SharedPreferences
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.storage.Repository
import com.trackmaster.app.storage.StorageAdapter
import com.trackmaster.app.throttle.ThrottleConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Engine 刷新（围栏增删改）时的状态迁移测试
 *
 * 守护的不变式：**替换 Engine 不能丢失用户当前的围栏停留状态**。
 *
 * 历史 bug：refreshEngineFences() 直接 new 一个 Engine，currentLeafId/currentEnterTs
 * 全部归零。用户在围栏内改名/改形状/批量改颜色后：
 * - getCurrentDurationSec() 立刻变成 0
 * - 若此时离开围栏，新 Engine 认为自己本来就不在围栏内，不生成 StaySegment，
 *   这段停留彻底丢失
 *
 * 另一个陷阱：旧 Engine 的 dispose() 会 transition(null) 主动关闭 segment，
 * 所以刷新场景必须用 dispose(closeActiveSegment = false)。
 */
class EngineRefreshStateTest {

    /** 内存版 StorageAdapter，与 EngineLocationPipelineTest 中的实现保持一致 */
    private class InMemoryStorageAdapter : StorageAdapter {
        val segments = mutableListOf<StaySegment>()
        private val openSegments = mutableListOf<StaySegment>()

        override suspend fun insertSegments(segments: List<StaySegment>) {
            this.segments.addAll(segments)
            openSegments.addAll(segments.filter { it.endTs == null })
        }

        override suspend fun querySegmentsByDate(dateKey: String): List<StaySegment> = segments.toList()

        override suspend fun queryOpenSegments(): List<StaySegment> = openSegments.toList()

        override suspend fun beginTransaction() = Unit

        override suspend fun commitTransaction() = Unit

        override suspend fun rollbackTransaction() = Unit
    }

    private fun squareFence(
        id: String,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ) = Geofence(
        id = id,
        name = id,
        type = GeoFenceType.POLYGON,
        polygon = listOf(
            GeoPoint(minLat, minLng),
            GeoPoint(minLat, maxLng),
            GeoPoint(maxLat, maxLng),
            GeoPoint(maxLat, minLng),
        ),
        bbox = BBox(minLng, minLat, maxLng, maxLat),
        depth = 0,
    )

    private fun fix(lat: Double, lng: Double, ts: Long) =
        LocationFix(lat = lat, lng = lng, accuracy = 10f, timestamp = ts, speed = 0f)

    /**
     * EngineHolder 只为 onLocationProcessed 回调持有 AppPreferences，
     * 初始化/刷新测试路径不会触发计数写入。JVM 单测没有 MockContext，
     * 这里用 ContextWrapper 覆盖 getSharedPreferences，返回一个最小实现。
     */
    private fun newHolder(repo: Repository, config: ThrottleConfig = ThrottleConfig()): EngineHolder {
        // Editor 的 putXxx 是链式调用（返回自身），apply/commit 有各自的返回类型
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor

        val prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            if (method.name == "edit") editor else defaultValue(method.returnType)
        } as SharedPreferences

        val context = object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        return EngineHolder(repo, config, AppPreferences(context))
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    /** 让 Engine 进入围栏，返回进入时间戳 */
    private suspend fun enterFence(engine: Engine, fence: Geofence, ts: Long) {
        engine.evaluateLocation(fix(29.0, 119.0, ts - 60_000))
        engine.processApprovedLocation(fix(29.0, 119.0, ts - 60_000))
        engine.evaluateLocation(fix(30.05, 120.05, ts))
        engine.processApprovedLocation(fix(30.05, 120.05, ts))
        assertEquals(fence.id, engine.currentLeafId)
    }

    @Test
    fun `refresh preserves current leaf and enter timestamp`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("home", 30.0, 30.1, 120.0, 120.1)
        val holder = newHolder(repo)
        holder.initialize(listOf(fence))

        val oldEngine = holder.engine
        val enterTs = 1_000_000L
        enterFence(oldEngine, fence, enterTs)

        holder.refresh(listOf(fence))
        val newEngine = holder.engine

        assertNotSame("refresh 必须真正替换 Engine 实例", oldEngine, newEngine)
        assertEquals("替换后必须保留 currentLeafId", "home", newEngine.currentLeafId)
        assertEquals("替换后必须保留 currentEnterTs", enterTs, newEngine.currentEnterTs)
    }

    @Test
    fun `old engine dispose with closeActiveSegment false does not emit segment`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("office", 30.0, 30.1, 120.0, 120.1)
        val engine = Engine(listOf(fence), repo, ThrottleConfig(), useSpatialIndex = false, useDynamicBuffer = false)

        enterFence(engine, fence, 1_000_000L)
        engine.dispose(closeActiveSegment = false)
        repo.flush()

        assertTrue(
            "刷新场景下旧 Engine 不得主动关闭停留（否则产生一条错误的已结束 segment）",
            adapter.segments.isEmpty()
        )
        // 默认语义保持不变：真正的释放仍然关闭活动停留
        engine.dispose(closeActiveSegment = true)
        repo.flush()
        assertEquals("默认 dispose 仍应关闭活动 segment", 1, adapter.segments.size)
    }

    @Test
    fun `stay continues across refresh and closes with original enter ts`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("home", 30.0, 30.1, 120.0, 120.1)
        val holder = newHolder(repo)
        holder.initialize(listOf(fence))

        val enterTs = 1_000_000L
        enterFence(holder.engine, fence, enterTs)
        holder.refresh(listOf(fence))
        repo.flush()

        // 刷新后用户离开围栏：必须用迁移后的进入时间生成完整 segment
        val leaveTs = enterTs + 120_000
        val newEngine = holder.engine
        newEngine.evaluateLocation(fix(29.5, 119.5, leaveTs))
        val closed = newEngine.processApprovedLocation(fix(29.5, 119.5, leaveTs))
        repo.flush()

        assertEquals("刷新后离开必须生成 segment", 1, adapter.segments.size)
        assertEquals("segment 的进入时间必须是迁移前的原始值", enterTs, closed!!.startTs)
        assertEquals(leaveTs, closed.endTs)
    }

    @Test
    fun `refresh drops state when fence no longer exists`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("deleted", 30.0, 30.1, 120.0, 120.1)
        val holder = newHolder(repo)
        holder.initialize(listOf(fence))
        enterFence(holder.engine, fence, 1_000_000L)

        holder.refresh(emptyList())

        assertNull("围栏已被删除时不能继续为无效围栏计时", holder.engine.currentLeafId)
        assertEquals(0L, holder.engine.currentEnterTs)
    }

    @Test
    fun `engine init after refresh does not overwrite migrated state`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("home", 30.0, 30.1, 120.0, 120.1)
        val holder = newHolder(repo)
        holder.initialize(listOf(fence))

        val enterTs = 1_000_000L
        enterFence(holder.engine, fence, enterTs)

        // 数据库里残留一条更早的 open segment（例如上次进程遗留）
        repo.enqueueSegment(StaySegment(leafGeofenceId = "stale", startTs = 500L))
        repo.flush()

        holder.refresh(listOf(fence))
        holder.engine.init()

        assertEquals("内存中的实时状态优先于 DB open segment", "home", holder.engine.currentLeafId)
        assertEquals(enterTs, holder.engine.currentEnterTs)
    }

    @Test
    fun `refresh keeps throttle config updated through holder`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val holder = newHolder(repo)
        holder.initialize(emptyList())

        // 模拟用户在设置页修改漂移阈值
        val updated = holder.engine.getScheduler().config.copy(driftThresholdM = 12.5)
        holder.updateThrottleConfig(updated)

        holder.refresh(emptyList())

        assertEquals(
            "refresh 重建 Engine 时不能把用户配置退回旧值",
            12.5,
            holder.engine.getScheduler().config.driftThresholdM,
            0.0
        )
    }
}
