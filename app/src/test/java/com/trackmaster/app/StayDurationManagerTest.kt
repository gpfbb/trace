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
                // ===== 第一天：从进入时间到进入日期午夜（次日0点） =====
                // 使用clear()确保Calendar状态干净
                val enterCalendar = Calendar.getInstance()
                enterCalendar.clear()
                enterCalendar.time = session.enterTime
                enterCalendar.add(Calendar.DAY_OF_MONTH, 1)
                enterCalendar.set(Calendar.HOUR_OF_DAY, 0)
                enterCalendar.set(Calendar.MINUTE, 0)
                enterCalendar.set(Calendar.SECOND, 0)
                enterCalendar.set(Calendar.MILLISECOND, 0)
                val midnightOfNextDay = enterCalendar.time

                val durationDay1 = (midnightOfNextDay.time - session.enterTime.time) / 1000
                if (durationDay1 > 0) {
                    addDailyDuration(enterDateKey, fenceId, fenceName, durationDay1)
                }

                // ===== 中间完整天 =====
                var dayCalendar = Calendar.getInstance()
                dayCalendar.clear()
                dayCalendar.time = midnightOfNextDay
                val exitCalendar = Calendar.getInstance()
                exitCalendar.clear()
                exitCalendar.time = exitTime
                exitCalendar.set(Calendar.HOUR_OF_DAY, 0)
                exitCalendar.set(Calendar.MINUTE, 0)
                exitCalendar.set(Calendar.SECOND, 0)
                exitCalendar.set(Calendar.MILLISECOND, 0)

                while (dayCalendar.before(exitCalendar)) {
                    val fullDayKey = getDateKey(dayCalendar.time)
                    addDailyDuration(fullDayKey, fenceId, fenceName, 86400L)
                    dayCalendar.add(Calendar.DAY_OF_MONTH, 1)
                }

                // ===== 最后一天：从当天0点到退出时间 =====
                val durationLastDay = (exitTime.time - exitCalendar.time.time) / 1000
                if (durationLastDay > 0) {
                    addDailyDuration(exitDateKey, fenceId, fenceName, durationLastDay)
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
        assertEquals(3600L, record?.totalDurationSeconds)
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
        assertEquals(3600L, stayManager.getDailyRecord(dateKey, "fence1")?.totalDurationSeconds)
        assertEquals(1800L, stayManager.getDailyRecord(dateKey, "fence2")?.totalDurationSeconds)
        assertEquals(3600L, stayManager.getDailyRecord(dateKey, "fence3")?.totalDurationSeconds)
    }

    @Test
    fun `test overnight stay`() {
        val calendar = Calendar.getInstance()
        calendar.set(2024, Calendar.JANUARY, 15, 23, 30, 0)
        calendar.set(Calendar.MILLISECOND, 0) // 确保毫秒为0以获得精确计算
        val enterTime = calendar.time

        stayManager.onFenceEnter("fence1", "家", enterTime)

        calendar.set(2024, Calendar.JANUARY, 16, 0, 30, 0)
        calendar.set(Calendar.MILLISECOND, 0) // 确保毫秒为0以获得精确计算
        val exitTime = calendar.time

        stayManager.onFenceExit("fence1", "家", exitTime)

        val day1Key = "2024-01-15"
        val day2Key = "2024-01-16"

        val day1Record = stayManager.getDailyRecord(day1Key, "fence1")
        val day2Record = stayManager.getDailyRecord(day2Key, "fence1")

        assertNotNull(day1Record)
        assertNotNull(day2Record)

        // 首日: 23:30 到 00:00 = 30分钟 = 1800秒
        assertEquals(1800L, day1Record!!.totalDurationSeconds)
        // 次日: 00:00 到 00:30 = 30分钟 = 1800秒
        assertEquals(1800L, day2Record!!.totalDurationSeconds)
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

        assertEquals(28800L, record?.totalDurationSeconds)
    }
}
