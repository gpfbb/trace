package com.trackmaster.app.core

import com.trackmaster.app.storage.Repository
import com.trackmaster.app.storage.StorageAdapter
import com.trackmaster.app.throttle.ThrottleConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Engine 定位处理链路测试
 *
 * 重点守护一条不变式：**通过了调度决策的定位点必须真的生成 StaySegment**。
 *
 * 历史 bug：`onLocationUpdate` 内部又调用了一次 `SmartScheduler.evaluate`，
 * 而同一个 fix 的第二次调用必然被时间闸门拦下（ts - lastProcessedTs == 0 < interval），
 * 导致 transition() 永不执行、stays 表永远为空。上面的调用看起来完全正常
 * （进入/退出事件、计时、UI 都在工作），所以这个 bug 潜伏了很久。
 */
class EngineLocationPipelineTest {

    /** 内存版 StorageAdapter，用来捕获 Engine 真正写入了什么 */
    private class InMemoryStorageAdapter : StorageAdapter {
        val segments = mutableListOf<StaySegment>()
        private var openSegments = mutableListOf<StaySegment>()
        private var depth = 0

        override suspend fun insertSegments(segments: List<StaySegment>) {
            this.segments.addAll(segments)
            // 未结束的 segment 视为"开放中"
            openSegments.addAll(segments.filter { it.endTs == null })
        }

        override suspend fun querySegmentsByDate(dateKey: String): List<StaySegment> = segments.toList()

        override suspend fun queryOpenSegments(): List<StaySegment> = openSegments.toList()

        override suspend fun beginTransaction() { depth++ }

        override suspend fun commitTransaction() { depth-- }

        override suspend fun rollbackTransaction() { depth-- }
    }

    /** 造一个正方形围栏，覆盖 [minLat]..[maxLat] x [minLng]..[maxLng] */
    private fun squareFence(id: String, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) = Geofence(
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
     * 核心用例：同一个定位点，先走调度决策，再走位置处理，
     * 必须能生成一条 StaySegment。
     */
    @Test
    fun `approved location generates stay segment`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("home", 30.0, 30.1, 120.0, 120.1)
        val engine = Engine(listOf(fence), repo, ThrottleConfig(), useSpatialIndex = false, useDynamicBuffer = false)

        // 第一个点：不在围栏内，被调度器放行（首次总是处理）
        val t0 = 1_000_000L
        val shouldProcess = engine.evaluateLocation(fix(29.0, 119.0, t0))
        assertTrue("首个定位点应被放行", shouldProcess)
        assertNull("不在围栏内，不应生成 segment", engine.processApprovedLocation(fix(29.0, 119.0, t0)))

        // 第二个点：进入围栏。同样的两段式流程，必须把 currentLeafId 置为该围栏
        val t1 = t0 + 60_000
        assertTrue(engine.evaluateLocation(fix(30.05, 120.05, t1)))
        // 进入本身不产生 segment（还没有可关闭的段），但状态必须已切换
        engine.processApprovedLocation(fix(30.05, 120.05, t1))
        assertEquals("进入围栏后 currentLeafId 必须是该围栏（P0 回归）", "home", engine.currentLeafId)
        assertTrue("进入时不应有 segment 落库", adapter.segments.isEmpty())

        // 第三个点：仍在同一围栏内，不应产生新 segment，状态保持不变
        val t2 = t1 + 60_000
        assertTrue(engine.evaluateLocation(fix(30.06, 120.06, t2)))
        assertNull("未发生围栏切换，不应生成新 segment", engine.processApprovedLocation(fix(30.06, 120.06, t2)))
        assertEquals("home", engine.currentLeafId)

        // 第四个点：离开围栏，应关闭前一段
        val t3 = t2 + 60_000
        assertTrue(engine.evaluateLocation(fix(29.5, 119.5, t3)))
        val exited = engine.processApprovedLocation(fix(29.5, 119.5, t3))
        assertNotNull("离开围栏必须生成 StaySegment（P0 回归）", exited)
        assertEquals("home", exited!!.leafGeofenceId)
        assertEquals(t1, exited.startTs)
        assertEquals(t3, exited.endTs)
        assertEquals(
            "segment 必须携带围栏名称快照（历史记录不能在改名后失去名字）",
            "home", exited.leafGeofenceName
        )
        repo.flush()
        assertEquals(1, adapter.segments.size)
        assertNull("离开后 currentLeafId 应为 null", engine.currentLeafId)
    }

    /**
     * 多次循环重复"评估→处理"，确认每个点都不会被自己的上一次决策挡住。
     * 这就是原 bug 的形态：第二个点被第一个点的决策误杀。
     */
    @Test
    fun `repeated evaluate-then-process never self-blocks`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val fence = squareFence("office", 30.0, 30.1, 120.0, 120.1)
        val engine = Engine(listOf(fence), repo, ThrottleConfig(), useSpatialIndex = false, useDynamicBuffer = false)

        var ts = 1_000_000L
        var generated = 0
        // 6 个点：外 → 内 ×4 → 外
        val path = listOf(29.0 to 119.0) + List(4) { 30.05 to 120.05 } + listOf(29.5 to 119.5)

        for ((lat, lng) in path) {
            ts += 60_000
            if (engine.evaluateLocation(fix(lat, lng, ts))) {
                if (engine.processApprovedLocation(fix(lat, lng, ts)) != null) generated++
            }
        }

        // 只有"离开"那一次会产生 segment（进入不产生）。若 bug 存在，这里会是 0。
        assertEquals("被放行的点必须能产生状态转移", 1, generated)
        repo.flush()
        assertEquals(1, adapter.segments.size)
        assertTrue("segment 必须有结束时间", adapter.segments.all { it.endTs != null })
    }

    /**
     * 深链不变式：只有最深的叶子围栏产生 segment，父级不产生。
     */
    @Test
    fun `only deepest leaf fence generates segment`() = runTest {
        val adapter = InMemoryStorageAdapter()
        val repo = Repository(adapter, batchSize = 100)
        val parent = squareFence("city", 30.0, 30.2, 120.0, 120.2).copy(depth = 0)
        val child = squareFence("office", 30.05, 30.15, 120.05, 120.15).copy(depth = 1)
        val engine = Engine(listOf(parent, child), repo, ThrottleConfig(), useSpatialIndex = false, useDynamicBuffer = false)

        val t0 = 1_000_000L
        engine.evaluateLocation(fix(29.0, 119.0, t0))
        engine.processApprovedLocation(fix(29.0, 119.0, t0))

        // 点在子围栏内 → 同时命中父子，但计时只能归属 child（leaf-first）
        val t1 = t0 + 60_000
        engine.evaluateLocation(fix(30.1, 120.1, t1))
        engine.processApprovedLocation(fix(30.1, 120.1, t1))
        assertEquals("leaf-first：currentLeafId 必须是最深层围栏", "office", engine.currentLeafId)
        assertEquals("两个围栏都应被记为命中", listOf("office", "city"), engine.currentMatchedFenceIds)

        // 离开子围栏但仍在父围栏内 → 关闭 child 段，父级接替
        val t2 = t1 + 60_000
        engine.evaluateLocation(fix(30.18, 120.18, t2))
        val seg = engine.processApprovedLocation(fix(30.18, 120.18, t2))
        assertNotNull("离开叶子围栏必须生成 StaySegment", seg)
        assertEquals("leaf-first：segment 记在最深层围栏上", "office", seg!!.leafGeofenceId)
        assertEquals(t1, seg.startTs)
        assertEquals(t2, seg.endTs)
        assertEquals("离开 child 后应回落到父围栏", "city", engine.currentLeafId)
        repo.flush()
        assertEquals(1, adapter.segments.size)
    }

    /**
     * 写失败时必须回滚状态，不能让 Engine 以为自己在围栏里。
     */
    @Test
    fun `state rolls back when repository write fails`() = runTest {
        val failing = object : StorageAdapter {
            override suspend fun insertSegments(segments: List<StaySegment>) =
                throw IllegalStateException("disk full")
            override suspend fun querySegmentsByDate(dateKey: String) = emptyList<StaySegment>()
            override suspend fun queryOpenSegments() = emptyList<StaySegment>()
            override suspend fun beginTransaction() = Unit
            override suspend fun commitTransaction() = Unit
            override suspend fun rollbackTransaction() = Unit
        }
        val repo = Repository(failing, batchSize = 1)
        val fence = squareFence("home", 30.0, 30.1, 120.0, 120.1)
        val engine = Engine(listOf(fence), repo, ThrottleConfig(), useSpatialIndex = false, useDynamicBuffer = false)

        val t0 = 1_000_000L
        engine.evaluateLocation(fix(29.0, 119.0, t0))
        engine.processApprovedLocation(fix(29.0, 119.0, t0))

        val t1 = t0 + 60_000
        engine.evaluateLocation(fix(30.05, 120.05, t1))
        val entered = engine.processApprovedLocation(fix(30.05, 120.05, t1))
        assertNull("进入围栏本身不产生 segment（无段可关闭）", entered)
        assertEquals("home", engine.currentLeafId)
        val enterTs = engine.currentEnterTs

        // 离开围栏时写库失败 → 状态必须保持在"仍在围栏内"，
        // 否则会凭空丢掉这段停留，且下次 transition 会用错误的时间
        val t2 = t1 + 60_000
        engine.evaluateLocation(fix(29.5, 119.5, t2))
        val exited = engine.processApprovedLocation(fix(29.5, 119.5, t2))

        assertNull("写库失败不得返回 segment", exited)
        assertEquals("写库失败后必须保持原状，不能假装已离开", "home", engine.currentLeafId)
        assertEquals("进入时间戳不得被写库失败破坏", enterTs, engine.currentEnterTs)
    }

    /**
     * 日期工具：跨天 segment 必须落到正确的日期桶。
     */
    @Test
    fun `date key uses local calendar`() {
        // 2026-10-18 12:00 UTC+8 → 本地日期 2026-10-18
        val ts = Date(2026 - 1900, 9, 18, 12, 0).time
        assertEquals("2026-10-18", Repository.formatDateKey(ts))
    }
}
