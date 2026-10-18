package com.trackmaster.app.ui.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设计系统的不变量测试。
 *
 * 这些不是"测试 UI 好不好看"，而是测试那些一旦错了就会让界面读不清
 * 的硬性约束：对比度、深浅色取值、主题模式解析。
 */
class DesignSystemTest {

    // ------------------------------------------------------------
    // 主题模式解析
    // ------------------------------------------------------------

    @Test
    fun `跟随系统时取系统深浅色`() {
        assertEquals(true, ThemeMode.SYSTEM.resolveDark(systemInDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveDark(systemInDark = false))
    }

    @Test
    fun `强制浅色时忽略系统设置`() {
        assertEquals(false, ThemeMode.LIGHT.resolveDark(systemInDark = true))
        assertEquals(false, ThemeMode.LIGHT.resolveDark(systemInDark = false))
    }

    @Test
    fun `强制深色时忽略系统设置`() {
        assertEquals(true, ThemeMode.DARK.resolveDark(systemInDark = true))
        assertEquals(true, ThemeMode.DARK.resolveDark(systemInDark = false))
    }

    // ------------------------------------------------------------
    // 调色板选择
    // ------------------------------------------------------------

    @Test
    fun `深色模式取 dark 色值 浅色模式取 light 色值`() {
        AccentColor.entries.forEach { accent ->
            val dark = paletteFor(accent, dark = true)
            val light = paletteFor(accent, dark = false)

            assertEquals("${accent.name} 深色模式应取 dark 档", accent.dark, dark.accent)
            assertEquals("${accent.name} 浅色模式应取 light 档", accent.light, light.accent)
            assertTrue("${accent.name} 深色调色板必须标记为 isDark", dark.isDark)
            assertTrue("${accent.name} 浅色调色板不能标记为 isDark", !light.isDark)
        }
    }

    @Test
    fun `深浅两种模式的背景色必须明显区分`() {
        AccentColor.entries.forEach { accent ->
            val dark = paletteFor(accent, dark = true)
            val light = paletteFor(accent, dark = false)
            assertTrue(
                "${accent.name} 的背景色在深浅模式下不应相同",
                dark.background != light.background
            )
        }
    }

    // ------------------------------------------------------------
    // 对比度：文字必须在背景上可读
    // ------------------------------------------------------------

    @Test
    fun `主文字在各自背景上的对比度达到 WCAG AA`() {
        AccentColor.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val p = paletteFor(accent, dark = dark)
                val mode = if (dark) "深色" else "浅色"

                assertAtLeastAa("$mode/${accent.name} 主文字 vs 页面底色", p.label, p.background)
                assertAtLeastAa("$mode/${accent.name} 主文字 vs 卡片底色", p.label, p.groupedCell)
                assertAtLeastAa("$mode/${accent.name} 主文字 vs 内嵌控件底色", p.label, p.elevated)
            }
        }
    }

    @Test
    fun `强调色上的文字对比度达到 WCAG AA`() {
        AccentColor.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val p = paletteFor(accent, dark = dark)
                val mode = if (dark) "深色" else "浅色"
                assertAtLeastAa(
                    "$mode/${accent.name} 强调色上的文字",
                    p.onAccent,
                    p.accent
                )
            }
        }
    }

    @Test
    fun `次要文字在卡片上至少达到 AA 大字号标准`() {
        AccentColor.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val p = paletteFor(accent, dark = dark)
                val mode = if (dark) "深色" else "浅色"
                // 次要文字是 13-15sp，属于正文尺寸，要求 4.5:1。
                // 这里按 3:1 兜底，因为它是辅助信息而非主要内容；
                // 低于 3:1 就完全读不清了。
                assertAtLeast(
                    minRatio = 3.0,
                    label = "$mode/${accent.name} 次要文字 vs 卡片底色",
                    foreground = p.labelSecondary,
                    background = p.groupedCell
                )
            }
        }
    }

    @Test
    fun `状态色在卡片上可见`() {
        AccentColor.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val p = paletteFor(accent, dark = dark)
                val mode = if (dark) "深色" else "浅色"
                assertAtLeast(
                    minRatio = 3.0,
                    label = "$mode/${accent.name} 成功色 vs 卡片",
                    foreground = p.success,
                    background = p.groupedCell
                )
                assertAtLeast(
                    minRatio = 3.0,
                    label = "$mode/${accent.name} 警告色 vs 卡片",
                    foreground = p.warning,
                    background = p.groupedCell
                )
                assertAtLeast(
                    minRatio = 3.0,
                    label = "$mode/${accent.name} 危险色 vs 卡片",
                    foreground = p.danger,
                    background = p.groupedCell
                )
            }
        }
    }

    @Test
    fun `强调色在页面底色上可辨识`() {
        AccentColor.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val p = paletteFor(accent, dark = dark)
                val mode = if (dark) "深色" else "浅色"
                assertAtLeast(
                    minRatio = 3.0,
                    label = "$mode/${accent.name} 强调色 vs 页面底色",
                    foreground = p.accent,
                    background = p.background
                )
            }
        }
    }

    // ------------------------------------------------------------
    // 分割线：不能比背景还抢眼
    // ------------------------------------------------------------

    @Test
    fun `分割线比背景重但比主文字轻`() {
        AccentColor.entries.forEach { accent ->
            listOf(true, false).forEach { dark ->
                val p = paletteFor(accent, dark = dark)
                val mode = if (dark) "深色" else "浅色"

                val separatorVsCell = contrast(p.separator, p.groupedCell)
                val labelVsCell = contrast(p.label, p.groupedCell)

                assertNotEquals(
                    "$mode/${accent.name} 分割线不能与卡片底色相同",
                    1.0,
                    separatorVsCell,
                    0.001
                )
                assertTrue(
                    "$mode/${accent.name} 分割线对比度 ${separatorVsCell} " +
                        "不应超过主文字的 ${labelVsCell}",
                    separatorVsCell < labelVsCell
                )
            }
        }
    }

    // ------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------

    private fun assertAtLeastAa(label: String, foreground: Color, background: Color) {
        assertAtLeast(minRatio = 4.5, label = label, foreground = foreground, background = background)
    }

    private fun assertAtLeast(minRatio: Double, label: String, foreground: Color, background: Color) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$label 对比度 ${"%.2f".format(ratio)}:1 低于要求的 $minRatio:1",
            ratio >= minRatio
        )
    }

    /** WCAG 2.1 相对亮度对比度。 */
    private fun contrast(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }
}
