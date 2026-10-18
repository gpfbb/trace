package com.trackmaster.app.ui.statistics

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环形图的几何换算。
 *
 * 这些数字直接决定环上每一段画多宽。算错的表现是：
 * 几段加起来超过 360 度（画回去重叠）或不足（留缺口），
 * 而且占比标签和实际弧长对不上。
 */
class DurationDonutChartTest {

    @Test
    fun `单段占满整个圆`() {
        val segments = buildDonutSegments(listOf(entry("a", 600)))

        assertEquals(1, segments.size)
        assertEquals(360f, segments[0].sweepAngle, 1e-3f)
        assertEquals(0f, segments[0].startAngle, 1e-3f)
        assertEquals(1f, segments[0].fraction, 1e-4f)
    }

    @Test
    fun `多段的角度总和等于 360 度`() {
        val segments = buildDonutSegments(
            listOf(entry("a", 600), entry("b", 300), entry("c", 100))
        )

        val total = segments.sumOf { it.sweepAngle.toDouble() }.toFloat()
        assertEquals("角度总和必须是 360，否则环上会有缺口或重叠", 360f, total, 1e-2f)
    }

    @Test
    fun `各段首尾相接 没有缝隙`() {
        val segments = buildDonutSegments(
            listOf(entry("a", 500), entry("b", 300), entry("c", 200))
        )

        for (i in 1 until segments.size) {
            assertEquals(
                "第 $i 段的起点应等于上一段的终点",
                segments[i - 1].startAngle + segments[i - 1].sweepAngle,
                segments[i].startAngle,
                1e-3f
            )
        }
    }

    @Test
    fun `按时长降序排列`() {
        val segments = buildDonutSegments(
            listOf(entry("small", 100), entry("big", 900), entry("mid", 500))
        )

        assertEquals(listOf("big", "mid", "small"), segments.map { it.fenceId })
    }

    @Test
    fun `零时长的围栏不产生扇段`() {
        val segments = buildDonutSegments(
            listOf(entry("a", 600), entry("zero", 0))
        )

        assertEquals(1, segments.size)
        assertEquals("a", segments[0].fenceId)
    }

    @Test
    fun `全部为零时返回空列表`() {
        assertTrue(buildDonutSegments(listOf(entry("a", 0), entry("b", 0))).isEmpty())
    }

    @Test
    fun `超出上限的合并成一段其他`() {
        // 8 个围栏，上限 6 → 前 5 个各自成段 + 1 段"其他"
        val entries = (1..8).map { entry("f$it", (1000 - it * 10).toLong()) }
        val segments = buildDonutSegments(entries, maxSegments = 6)

        assertEquals(6, segments.size)
        assertEquals(OTHER_SEGMENT_ID, segments.last().fenceId)
        // "其他"的时长 = 被合并掉的三个之和
        val expectedOthers = entries.sortedByDescending { it.durationSeconds }
            .drop(5).sumOf { it.durationSeconds }
        assertEquals(expectedOthers, segments.last().durationSeconds)
    }

    @Test
    fun `不超过上限时不产生其他段`() {
        val entries = (1..3).map { entry("f$it", 100L) }
        val segments = buildDonutSegments(entries, maxSegments = 6)

        assertEquals(3, segments.size)
        assertTrue(segments.none { it.fenceId == OTHER_SEGMENT_ID })
    }

    @Test
    fun `占比之和为 1`() {
        val segments = buildDonutSegments(
            listOf(entry("a", 137), entry("b", 251), entry("c", 400))
        )

        val sum = segments.sumOf { it.fraction.toDouble() }.toFloat()
        assertEquals(1f, sum, 1e-4f)
    }

    // ------------------------------------------------------------

    private fun entry(id: String, seconds: Long) = DonutEntry(
        fenceId = id,
        label = id,
        color = Color.Red,
        durationSeconds = seconds
    )
}
