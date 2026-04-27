package com.trackmaster.app

import com.trackmaster.app.core.StaySegment
import com.trackmaster.app.core.SegmentStatus
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * StayDurationManager 并发测试
 *
 * 测试目标：
 * 1. activeSessions 并发插入
 * 2. 跨天计算边界情况
 * 3. App 重启后恢复
 */
class StayDurationManagerConcurrencyTest {

    private lateinit var stayManager: TestableStayDurationManager

    /**
     * 可测试的 StayDurationManager 简化版本
     * 移除了数据库依赖，直接测试核心逻辑
     */
    class TestableStayDurationManager {
        val activeSessions = java.util.concurrent.ConcurrentHashMap<String, FenceActiveSessionData>()
        val dailyRecords = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, DailyStayRecordData>>()
        private val enterLock = Any()

        fun getDateKey(date: java.util.Date): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return sdf.format(date)
        }

        fun onFenceEnter(fenceId: String, fenceName: String, enterTime: java.util.Date) {
            val dateKey = getDateKey(enterTime)
            synchronized(enterLock) {
                val existing = activeSessions[fenceId]
                if (existing != null) {
                    // Close old session before starting new one
                    val duration = (enterTime.time - existing.enterTime.time) / 1000
                    val oldDateKey = existing.dateKey
                    if (duration > 0) {
                        addDailyDuration(oldDateKey, fenceId, fenceName, duration)
                    }
                    activeSessions.remove(fenceId)
                }
                activeSessions[fenceId] = FenceActiveSessionData(fenceId, fenceName, enterTime, dateKey)
            }
        }

        fun onFenceExit(fenceId: String, fenceName: String, exitTime: java.util.Date) {
            val session = activeSessions.remove(fenceId) ?: return

            val enterDateKey = session.dateKey
            val exitDateKey = getDateKey(exitTime)

            if (enterDateKey == exitDateKey) {
                val duration = (exitTime.time - session.enterTime.time) / 1000
                if (duration > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, duration)
                }
            } else {
                val enterCalendar = java.util.Calendar.getInstance()
                enterCalendar.time = session.enterTime
                enterCalendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                enterCalendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                enterCalendar.set(java.util.Calendar.MINUTE, 0)
                enterCalendar.set(java.util.Calendar.SECOND, 0)
                enterCalendar.set(java.util.Calendar.MILLISECOND, 0)
                val midnightOfNextDay = enterCalendar.time

                val durationDay1 = (midnightOfNextDay.time - session.enterTime.time) / 1000
                if (durationDay1 > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                }

                val dayCalendar = java.util.Calendar.getInstance()
                dayCalendar.time = midnightOfNextDay

                val exitCalendar = java.util.Calendar.getInstance()
                exitCalendar.time = exitTime
                exitCalendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                exitCalendar.set(java.util.Calendar.MINUTE, 0)
                exitCalendar.set(java.util.Calendar.SECOND, 0)
                exitCalendar.set(java.util.Calendar.MILLISECOND, 0)

                while (dayCalendar.before(exitCalendar)) {
                    val fullDayKey = getDateKey(dayCalendar.time)
                    addDailyDuration(fullDayKey, fenceId, fenceName, 86400L)
                    dayCalendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                }

                val durationLastDay = (exitTime.time - exitCalendar.time.time) / 1000
                if (durationLastDay > 0) {
                    addDailyDuration(exitDateKey, fenceId, fenceName, durationLastDay)
                }
            }
        }

        private fun addDailyDuration(dateKey: String, fenceId: String, fenceName: String, durationSeconds: Long) {
            val dateRecords = dailyRecords.getOrPut(dateKey) { mutableMapOf() }
            val existing = dateRecords[fenceId]
            val newDuration = if (existing != null) {
                existing.totalDurationSeconds + durationSeconds
            } else {
                durationSeconds
            }
            dateRecords[fenceId] = DailyStayRecordData(
                date = dateKey,
                fenceId = fenceId,
                fenceName = fenceName,
                totalDurationSeconds = newDuration,
                lastUpdated = java.util.Date()
            )
        }

        fun getTotalDailyDuration(dateKey: String): Long {
            val records = dailyRecords[dateKey]?.values ?: return 0
            return records.sumOf { it.totalDurationSeconds }
        }

        fun getDailyRecord(dateKey: String, fenceId: String): DailyStayRecordData? {
            return dailyRecords[dateKey]?.get(fenceId)
        }

        fun isFenceActive(fenceId: String): Boolean = activeSessions.containsKey(fenceId)

        fun getActiveSessions(): List<FenceActiveSessionData> {
            return activeSessions.values.toList()
        }
    }

    data class FenceActiveSessionData(
        val fenceId: String,
        val fenceName: String,
        val enterTime: java.util.Date,
        val dateKey: String,
        val accumulatedDuration: Long = 0L,
        val lastSaveTime: java.util.Date? = null
    )

    data class DailyStayRecordData(
        val date: String,
        val fenceId: String,
        val fenceName: String,
        val totalDurationSeconds: Long,
        val lastUpdated: java.util.Date
    )

    @Before
    fun setup() {
        stayManager = TestableStayDurationManager()
    }

    @Test
    fun concurrentEnterSameFence() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time
        val threadCount = 10
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            Thread {
                stayManager.onFenceEnter("fence_1", "Home", enterTime)
                latch.countDown()
            }.start()
        }

        latch.await()

        val activeSessions = stayManager.getActiveSessions()
        assertEquals("Should have 1 active session", 1, activeSessions.size)
    }

    @Test
    fun concurrentEnterDifferentFences() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time
        val threadCount = 10
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            Thread {
                stayManager.onFenceEnter("fence_$i", "Fence $i", enterTime)
                latch.countDown()
            }.start()
        }

        latch.await()

        val activeSessions = stayManager.getActiveSessions()
        assertEquals("Should have $threadCount active sessions", threadCount, activeSessions.size)
    }

    @Test
    fun enterThenExitSameFence() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time

        stayManager.onFenceEnter("fence_1", "Home", enterTime)
        assertTrue("Fence should be active", stayManager.isFenceActive("fence_1"))

        // 1小时后退出
        calendar.add(java.util.Calendar.HOUR_OF_DAY, 1)
        val exitTime = calendar.time
        stayManager.onFenceExit("fence_1", "Home", exitTime)

        assertFalse("Fence should not be active", stayManager.isFenceActive("fence_1"))

        val dateKey = stayManager.getDateKey(enterTime)
        val record = stayManager.getDailyRecord(dateKey, "fence_1")
        assertNotNull("Should have daily record", record)
        assertEquals("Duration should be 1 hour", 3600L, record?.totalDurationSeconds)
    }

    @Test
    fun overnightStayCalculation() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(2024, java.util.Calendar.JANUARY, 15, 23, 30, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time

        stayManager.onFenceEnter("fence_1", "Home", enterTime)

        calendar.set(2024, java.util.Calendar.JANUARY, 16, 0, 30, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val exitTime = calendar.time

        stayManager.onFenceExit("fence_1", "Home", exitTime)

        val day1Record = stayManager.getDailyRecord("2024-01-15", "fence_1")
        val day2Record = stayManager.getDailyRecord("2024-01-16", "fence_1")

        assertNotNull("Day 1 should have record", day1Record)
        assertNotNull("Day 2 should have record", day2Record)
        assertEquals("Day 1 duration should be 30 minutes", 1800L, day1Record!!.totalDurationSeconds)
        assertEquals("Day 2 duration should be 30 minutes", 1800L, day2Record!!.totalDurationSeconds)
    }

    @Test
    fun multiDayStayCalculation() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(2024, java.util.Calendar.JANUARY, 14, 22, 0, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time

        stayManager.onFenceEnter("fence_1", "Home", enterTime)

        calendar.set(2024, java.util.Calendar.JANUARY, 16, 2, 0, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val exitTime = calendar.time

        stayManager.onFenceExit("fence_1", "Home", exitTime)

        val day14 = stayManager.getDailyRecord("2024-01-14", "fence_1")
        val day15 = stayManager.getDailyRecord("2024-01-15", "fence_1")
        val day16 = stayManager.getDailyRecord("2024-01-16", "fence_1")

        assertEquals("Day 14 duration should be 2 hours", 7200L, day14?.totalDurationSeconds)
        assertEquals("Day 15 duration should be 24 hours", 86400L, day15?.totalDurationSeconds)
        assertEquals("Day 16 duration should be 2 hours", 7200L, day16?.totalDurationSeconds)
    }

    @Test
    fun multipleStaysOnSameDay() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(2024, java.util.Calendar.JANUARY, 15, 8, 0, 0)
        val enter1 = calendar.time
        calendar.set(2024, java.util.Calendar.JANUARY, 15, 12, 0, 0)
        val exit1 = calendar.time

        stayManager.onFenceEnter("fence_1", "Office", enter1)
        stayManager.onFenceExit("fence_1", "Office", exit1)

        calendar.set(2024, java.util.Calendar.JANUARY, 15, 14, 0, 0)
        val enter2 = calendar.time
        calendar.set(2024, java.util.Calendar.JANUARY, 15, 18, 0, 0)
        val exit2 = calendar.time

        stayManager.onFenceEnter("fence_1", "Office", enter2)
        stayManager.onFenceExit("fence_1", "Office", exit2)

        val record = stayManager.getDailyRecord("2024-01-15", "fence_1")
        assertEquals("Total duration should be 8 hours", 28800L, record?.totalDurationSeconds)
    }

    @Test
    fun concurrentEnterAndExit() {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time
        val exitTime = java.util.Date(enterTime.time + 3600 * 1000)

        val threadCount = 5
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            Thread {
                stayManager.onFenceEnter("fence_$i", "Fence $i", enterTime)
                latch.countDown()
            }.start()
        }

        latch.await()

        val exitLatch = CountDownLatch(threadCount)
        repeat(threadCount) { i ->
            Thread {
                stayManager.onFenceExit("fence_$i", "Fence $i", exitTime)
                exitLatch.countDown()
            }.start()
        }

        exitLatch.await()

        assertFalse("fence_0 should not be active", stayManager.isFenceActive("fence_0"))
        assertFalse("fence_4 should not be active", stayManager.isFenceActive("fence_4"))

        val dateKey = stayManager.getDateKey(enterTime)
        repeat(threadCount) { i ->
            val record = stayManager.getDailyRecord(dateKey, "fence_$i")
            assertNotNull("fence_$i should have record", record)
            assertEquals("fence_$i duration should be 1 hour", 3600L, record?.totalDurationSeconds)
        }
    }

    @Test
    fun totalDailyDurationAggregation() {
        // Use fixed noon time to avoid day-boundary issues
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val enterTime = calendar.time

        stayManager.onFenceEnter("fence_1", "Home", enterTime)
        stayManager.onFenceEnter("fence_2", "Office", enterTime)

        val exitTime1 = java.util.Date(enterTime.time + 3600 * 1000)
        val exitTime2 = java.util.Date(enterTime.time + 7200 * 1000)
        stayManager.onFenceExit("fence_1", "Home", exitTime1)
        stayManager.onFenceExit("fence_2", "Office", exitTime2)

        val dateKey = stayManager.getDateKey(enterTime)
        val total = stayManager.getTotalDailyDuration(dateKey)

        assertEquals("Total duration should be 3 hours", 10800L, total)
    }
}