package com.trackmaster.app.storage

import com.trackmaster.app.core.StaySegment
import com.trackmaster.app.core.SegmentStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository 写缓冲测试
 *
 * 重点守护：**数据库瞬时失败不能把停留记录永久丢掉**。
 * 历史问题：flush() 失败后保留 buffer 并抛出异常，但没有任何重试，
 * 调用方（Engine.transition）catch 后只回滚状态、不重新入队，
 * 于是这批 segment 永远滞留内存，进程一死就没了。
 */
class RepositoryFlushTest {

    /** 前 failTimes 次插入抛异常，之后成功 */
    private class FlakyAdapter(private val failTimes: Int) : StorageAdapter {
        val stored = mutableListOf<StaySegment>()
        var attempts = 0
            private set

        override suspend fun insertSegments(segments: List<StaySegment>) {
            attempts++
            if (attempts <= failTimes) throw IllegalStateException("db busy (attempt $attempts)")
            stored.addAll(segments)
        }

        override suspend fun querySegmentsByDate(dateKey: String): List<StaySegment> = stored.toList()

        override suspend fun queryOpenSegments(): List<StaySegment> = stored.filter { it.endTs == null }

        override suspend fun beginTransaction() = Unit
        override suspend fun commitTransaction() = Unit
        override suspend fun rollbackTransaction() = Unit
    }

    private fun seg(id: String, start: Long, end: Long? = null) = StaySegment(
        id = id,
        leafGeofenceId = "fence",
        startTs = start,
        endTs = end,
        status = if (end == null) SegmentStatus.ACTIVE else SegmentStatus.COMPLETED,
    )

    @Test
    fun `flush retries after transient failure and keeps data`() = runTest {
        val adapter = FlakyAdapter(failTimes = 2)
        val repo = Repository(adapter, batchSize = 100)

        repo.enqueueSegment(seg("a", 1000L, 2000L))
        repo.enqueueSegment(seg("b", 3000L, 4000L))
        repo.flush()

        assertEquals("重试后必须落库", listOf("a", "b"), adapter.stored.map { it.id })
        assertEquals("前两次失败 + 第三次成功", 3, adapter.attempts)

        // flush 成功后 buffer 应清空，不应重复写入
        repo.flush()
        assertEquals("buffer 已清空，不应重复落库", 3, adapter.attempts)
    }

    @Test
    fun `flush gives up after max retries but keeps buffer intact`() = runTest {
        val adapter = FlakyAdapter(failTimes = 999)
        val repo = Repository(adapter, batchSize = 100)

        repo.enqueueSegment(seg("a", 1000L, 2000L))

        var threw = false
        try {
            repo.flush()
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue("超过重试上限必须抛出，让上层知道落库失败", threw)

        // 数据仍在 buffer 中：后续成功时还能补写，不会静默丢失
        repo.enqueueSegment(seg("b", 5000L, 6000L))
        val recoverable = repo.getSegmentsByDate("1970-01-01")
        assertTrue("失败的 segment 必须仍在可查询范围内", recoverable.any { it.id == "a" })
        assertTrue("新入队的 segment 也在", recoverable.any { it.id == "b" })
    }

    @Test
    fun `auto flush triggers at batch size`() = runTest {
        val adapter = FlakyAdapter(failTimes = 0)
        val repo = Repository(adapter, batchSize = 3)

        repo.enqueueSegment(seg("a", 1000L, 2000L))
        repo.enqueueSegment(seg("b", 3000L, 4000L))
        assertEquals("未满一批不落库", 0, adapter.stored.size)

        repo.enqueueSegment(seg("c", 5000L, 6000L))
        assertEquals("满一批自动 flush", listOf("a", "b", "c"), adapter.stored.map { it.id })
    }

    @Test
    fun `read merges pending buffer with stored segments`() = runTest {
        val adapter = FlakyAdapter(failTimes = 0)
        val repo = Repository(adapter, batchSize = 100)

        repo.enqueueSegment(seg("a", 1000L, 2000L))
        repo.flush()
        // 同一天再入队一条但未 flush
        repo.enqueueSegment(seg("b", 3000L, 4000L))

        val all = repo.getSegmentsByDate("1970-01-01")
        assertEquals("已落库 + 未落库的都应可见", listOf("a", "b"), all.map { it.id })
    }
}
