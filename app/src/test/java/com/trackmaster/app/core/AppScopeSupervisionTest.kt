package com.trackmaster.app.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用级协程作用域的行为测试
 *
 * 守护的不变式：**一个定位处理协程失败，不能连带废掉后续所有定位处理**。
 *
 * 历史问题：`appScope = CoroutineScope(Dispatchers.IO)` 没有 SupervisorJob。
 * 普通 Job 的语义是"任一子协程失败即取消整个父作用域"，于是一次
 * Repository.flush 重试耗尽（或任何未捕获异常）就会让 appScope 永久失效，
 * 之后每一次 checkGeoFences 的 launch 都变成空操作，而且没有任何报错。
 *
 * 这个测试不依赖 Application（无法在 JVM 单测里实例化），
 * 而是直接验证两种作用域配置的语义差异，确保修复方向正确。
 */
class AppScopeSupervisionTest {

    @Test
    fun `plain Job scope dies after child failure`() = runTest {
        val executed = AtomicInteger(0)
        // 模拟修复前的配置：没有 SupervisorJob
        val scope = CoroutineScope(Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })

        // 第一个协程失败
        val failing = scope.launch { throw IllegalStateException("flush retries exhausted") }
        runCatching { failing.join() }

        // 之后的协程是否还能执行？
        val after = scope.async { executed.incrementAndGet() }
        runCatching { after.await() }

        assertEquals(
            "普通 Job 作用域在子协程失败后应当拒绝执行后续任务（这是修复要避免的行为）",
            0, executed.get()
        )
    }

    @Test
    fun `supervisor scope keeps working after child failure`() = runTest {
        val executed = AtomicInteger(0)
        // 修复后的配置：SupervisorJob + 异常处理器
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
        )

        val failing = scope.launch { throw IllegalStateException("flush retries exhausted") }
        runCatching { failing.join() }

        // 后续协程必须照常执行 —— 定位处理不能因为一次写库失败就全线停摆
        val after = scope.async { executed.incrementAndGet() }
        assertEquals(1, after.await())
        assertEquals("SupervisorJob 下后续任务应正常执行", 1, executed.get())
    }

    @Test
    fun `supervisor scope runs independent work concurrently`() = runTest {
        val completed = AtomicInteger(0)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> }
        )

        // 5 个任务并发，其中 2 个失败
        val jobs = (0 until 5).map { i ->
            scope.async {
                if (i % 3 == 0) throw RuntimeException("task $i failed")
                completed.incrementAndGet()
            }
        }
        jobs.forEach { runCatching { it.await() } }

        assertEquals("除了失败的两个，其余三个都应完成", 3, completed.get())
    }
}
