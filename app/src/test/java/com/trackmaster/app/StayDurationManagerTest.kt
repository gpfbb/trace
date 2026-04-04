package com.trackmaster.app

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.text.SimpleDateFormat
import java.util.*

class StayDurationManagerTest {

    private lateinit var stayManager: MockStayDurationManager
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    class MockStayDurationManager {
        val activeSessions = mutableMapOf<String, FenceActiveSession>()
        val dailyRecords = mutableMapOf<String, MutableMap<String, DailyStayRecord>>()

        fun getDateKey(date: Date): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
        }

        fun onFenceEnter(fenceId: String, fenceName: String, enterTime: Date) {
            val dateKey = getDateKey(enterTime)
            activeSessions[fenceId] = FenceActiveSession(fenceId, fenceName, enterTime, dateKey)
        }

        fun onFenceExit(fenceId: String, fenceName: String, exitTime: Date) {
            val session = activeSessions.remove(fenceId) ?: return

            val enterDateKey = session.dateKey
            val exitDateKey = getDateKey(exitTime)

            if (enterDateKey == exitDateKey) {
                val duration = (exitTime.time - session.enterTime.time) / 1000
                if (duration > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, duration)
                }
            } else {
                val calendar = Calendar.getInstance()
                calendar.time = session.enterTime
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.set(Calendar.MILLISECOND, 999)
                val midnight = calendar.time

                val durationDay1 = (midnight.time - session.enterTime.time) / 1000
                if (durationDay1 > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                }

                calendar.time = exitTime
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val nextMidnight = calendar.time

                val durationDay2 = (exitTime.time - nextMidnight.time) / 1000
                if (durationDay2 > 0) {
                    addDailyDuration(exitDateKey, fenceId, fenceName, durationDay2)
                }
            }
        }

        private fun addDailyDuration(dateKey: String, fenceId: String, fenceName: String, duration: Long) {
            val dateMap = dailyRecords.getOrPut(dateKey) { mutableMapOf() }
            val existing = dateMap[fenceId]
            val newDuration = if (existing != null) {
                existing.totalDurationSeconds + duration
            } else {
                duration
            }
            dateMap[fenceId] = DailyStayRecord(
                date = dateKey,
                fenceId = fenceId,
                fenceName = fenceName,
                totalDurationSeconds = newDuration,
                lastUpdated = Date()
            )
        }

        fun getTotalDailyDuration(dateKey: String): Long {
            val records = dailyRecords[dateKey]?.values ?: return 0
            return records.sumOf { it.totalDurationSeconds }
        }

        fun getDailyRecord(dateKey: String, fenceId: String): DailyStayRecord? {
            return dailyRecords[dateKey]?.get(fenceId)
        }

        fun isFenceActive(fenceId: String): Boolean = activeSessions.containsKey(fenceId)
    }

    @Before
    fun setup() {
        stayManager = MockStayDurationManager()
    }

    @Test
    fun `test single fence enter and exit`() {
        val enterTime = Date()
        stayManager.onFenceEnter("fence1", "家", enterTime)

        assertTrue(stayManager.isFenceActive("fence1"))

        val exitTime = Date(enterTime.time + 3600 * 1000)
        stayManager.onFenceExit("fence1", "家", exitTime)

        assertFalse(stayManager.isFenceActive("fence1"))

        val dateKey = stayManager.getDateKey(enterTime)
        val record = stayManager.getDailyRecord(dateKey, "fence1")
        assertNotNull(record)
        assertEquals(3600, record?.totalDurationSeconds)
    }

    @Test
    fun `test multiple nested fences at same time`() {
        val time = Date()
        stayManager.onFenceEnter("fence1", "国家", time)
        stayManager.onFenceEnter("fence2", "省", time)
        stayManager.onFenceEnter("fence3", "市", time)

        assertTrue(stayManager.isFenceActive("fence1"))
        assertTrue(stayManager.isFenceActive("fence2"))
        assertTrue(stayManager.isFenceActive("fence3"))

        val exitTime = Date(time.time + 1800 * 1000)
        stayManager.onFenceExit("fence2", "省", exitTime)

        assertTrue(stayManager.isFenceActive("fence1"))
        assertFalse(stayManager.isFenceActive("fence2"))
        assertTrue(stayManager.isFenceActive("fence3"))

        val finalExitTime = Date(time.time + 3600 * 1000)
        stayManager.onFenceExit("fence1", "国家", finalExitTime)
        stayManager.onFenceExit("fence3", "市", finalExitTime)

        val dateKey = stayManager.getDateKey(time)
        assertEquals(3600, stayManager.getDailyRecord(dateKey, "fence1")?.totalDurationSeconds)
        assertEquals(1800, stayManager.getDailyRecord(dateKey, "fence2")?.totalDurationSeconds)
        assertEquals(3600, stayManager.getDailyRecord(dateKey, "fence3")?.totalDurationSeconds)
    }

    @Test
    fun `test overnight stay`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.JANUARY, 15, 23, 30, 0)
        val enterTime = calendar.time

        stayManager.onFenceEnter("fence1", "家", enterTime)

        calendar.set(2024, Calendar.JANUARY, 16, 0, 30, 0)
        val exitTime = calendar.time

        stayManager.onFenceExit("fence1", "家", exitTime)

        val day1Key = "2024-01-15"
        val day2Key = "2024-01-16"

        val day1Record = stayManager.getDailyRecord(day1Key, "fence1")
        val day2Record = stayManager.getDailyRecord(day2Key, "fence1")

        assertNotNull(day1Record)
        assertNotNull(day2Record)

        assertTrue(day1Record!!.totalDurationSeconds in 1799..1801)
        assertTrue(day2Record!!.totalDurationSeconds in 1799..1801)
    }

    @Test
    fun `test multiple stays on same day`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.JANUARY, 15, 8, 0, 0)
        val enter1 = calendar.time
        calendar.set(2024, Calendar.JANUARY, 15, 12, 0, 0)
        val exit1 = calendar.time

        stayManager.onFenceEnter("fence1", "公司", enter1)
        stayManager.onFenceExit("fence1", "公司", exit1)

        calendar.set(2024, Calendar.JANUARY, 15, 14, 0, 0)
        val enter2 = calendar.time
        calendar.set(2024, Calendar.JANUARY, 15, 18, 0, 0)
        val exit2 = calendar.time

        stayManager.onFenceEnter("fence1", "公司", enter2)
        stayManager.onFenceExit("fence1", "公司", exit2)

        val dateKey = "2024-01-15"
        val record = stayManager.getDailyRecord(dateKey, "fence1")

        assertEquals(28800, record?.totalDurationSeconds)
    }
}
