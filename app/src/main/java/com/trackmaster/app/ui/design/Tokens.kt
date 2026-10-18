package com.trackmaster.app.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token —— 新 UI 的唯一真源。
 *
 * 规则：任何屏幕都不应该直接写 `Color(0x...)` / `16.dp` / `14.sp`。
 * 颜色从 [AppPalette] 取，间距从 [Space] 取，圆角从 [Radius] 取，字号从 [AppType] 取。
 *
 * 视觉方向：分组列表（grouped list）。一个 section 是一张圆角卡片，
 * 卡片里放多行，行与行之间用内缩分割线。卡片本身不发光、不加渐变。
 */

// ============================================================
// 主题模式
// ============================================================

enum class ThemeMode {
    /** 跟随系统深浅色 */
    SYSTEM,

    /** 始终浅色 */
    LIGHT,

    /** 始终深色 */
    DARK;

    /** 在拿到系统深浅色后，解析出实际是否用深色 */
    fun resolveDark(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }
}

// ============================================================
// 强调色
// ============================================================

/**
 * 强调色。深浅色模式各有一份色值，因为同一个色在浅色底上要压暗、
 * 在深色底上要提亮才能保证对比度。
 *
 * @param light 浅色模式下的色值
 * @param dark 深色模式下的色值
 * @param onLight 浅色模式下压在 [light] 上的文字色
 * @param onDark 深色模式下压在 [dark] 上的文字色
 */
enum class AccentColor(
    val light: Color,
    val dark: Color,
    val onLight: Color,
    val onDark: Color
) {
    // 注意：深色档里几个偏亮的色（CORAL/BLUE/RED/PURPLE/ROSE）用黑字而不是白字。
    // 白字在这些色上只有 2.5-3.7:1，达不到 WCAG AA；黑字能到 5.7-7.8:1。
    // 这个取值由 DesignSystemTest 的对比度用例守住。
    CORAL(Color(0xFFC2410C), Color(0xFFFF7F50), Color.White, Color.Black),
    BLUE(Color(0xFF0062D6), Color(0xFF0A84FF), Color.White, Color.Black),
    RED(Color(0xFFD70015), Color(0xFFFF453A), Color.White, Color.Black),
    EMERALD(Color(0xFF00875A), Color(0xFF30D158), Color.White, Color.Black),
    PURPLE(Color(0xFF8944AB), Color(0xFFBF5AF2), Color.White, Color.Black),
    ORANGE(Color(0xFFC2410C), Color(0xFFFF9F0A), Color.White, Color.Black),
    TEAL(Color(0xFF00838F), Color(0xFF40CBE0), Color.White, Color.Black),
    ROSE(Color(0xFFD81B60), Color(0xFFFF6482), Color.White, Color.Black);

    fun tint(isDark: Boolean): Color = if (isDark) dark else light

    fun on(isDark: Boolean): Color = if (isDark) onDark else onLight
}

// ============================================================
// 调色板
// ============================================================

/**
 * 语义化调色板。所有字段都有明确用途，屏幕代码只读这些字段，不读具体色值。
 */
@Immutable
data class AppPalette(
    val isDark: Boolean,

    // 强调色
    val accent: Color,
    val onAccent: Color,
    /** 强调色的淡背景，用于徽章、选中行的高亮 */
    val accentSubtle: Color,

    // 背景与容器
    /** 页面底色 */
    val background: Color,
    /** 分组卡片底色 */
    val groupedCell: Color,
    /** 卡片内再嵌入的控件底色（如输入框、分段控件槽） */
    val elevated: Color,
    /** 无底色控件（chip、头像占位）用的填充色 */
    val fill: Color,

    // 分割线与边框
    val separator: Color,
    val border: Color,

    // 文字
    val label: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val labelDisabled: Color,

    // 语义状态色
    val success: Color,
    val warning: Color,
    val danger: Color,
    val onDanger: Color
)

fun lightPalette(accent: AccentColor): AppPalette = AppPalette(
    isDark = false,
    accent = accent.light,
    onAccent = accent.onLight,
    accentSubtle = accent.light.copy(alpha = 0.12f),
    background = Color(0xFFF2F2F7),
    groupedCell = Color(0xFFFFFFFF),
    elevated = Color(0xFFFFFFFF),
    fill = Color(0xFF787880).copy(alpha = 0.12f),
    separator = Color(0xFFC6C6C8),
    border = Color(0xFFE5E5EA),
    label = Color(0xFF000000),
    labelSecondary = Color(0xFF3C3C43).copy(alpha = 0.60f),
    labelTertiary = Color(0xFF3C3C43).copy(alpha = 0.30f),
    labelDisabled = Color(0xFF3C3C43).copy(alpha = 0.18f),
    // 语义色在浅色底上要压暗才能读清：iOS 的 systemGreen/systemOrange
    // 直接放白底只有 2.2:1。这里用深一档的值，由对比度测试守住。
    success = Color(0xFF1F7A33),
    warning = Color(0xFFA85A00),
    danger = Color(0xFFD70015),
    onDanger = Color.White
)

fun darkPalette(accent: AccentColor): AppPalette = AppPalette(
    isDark = true,
    accent = accent.dark,
    onAccent = accent.onDark,
    accentSubtle = accent.dark.copy(alpha = 0.20f),
    background = Color(0xFF000000),
    groupedCell = Color(0xFF1C1C1E),
    elevated = Color(0xFF2C2C2E),
    fill = Color(0xFF787880).copy(alpha = 0.24f),
    separator = Color(0xFF38383A),
    border = Color(0xFF38383A),
    label = Color(0xFFFFFFFF),
    labelSecondary = Color(0xFFEBEBF5).copy(alpha = 0.60f),
    labelTertiary = Color(0xFFEBEBF5).copy(alpha = 0.30f),
    labelDisabled = Color(0xFFEBEBF5).copy(alpha = 0.18f),
    success = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    onDanger = Color.White
)

fun paletteFor(accent: AccentColor, dark: Boolean): AppPalette =
    if (dark) darkPalette(accent) else lightPalette(accent)

// ============================================================
// 间距
// ============================================================

/** 4dp 基准的间距阶梯。 */
object Space {
    /** 2dp —— 几乎贴在一起，用于图标与文字的紧邻 */
    val xxs = 2.dp
    /** 4dp —— 行内元素之间 */
    val xs = 4.dp
    /** 8dp —— 同一组内的小间隔 */
    val sm = 8.dp
    /** 12dp —— 卡片内元素间隔 */
    val md = 12.dp
    /** 16dp —— 页面左右边距、卡片内边距 */
    val lg = 16.dp
    /** 20dp —— section 之间 */
    val xl = 20.dp
    /** 24dp —— 大块之间 */
    val xxl = 24.dp
    /** 32dp —— 页面顶部留白 */
    val xxxl = 32.dp

    /** 页面内容的左右边距 */
    val screenHorizontal = 16.dp
    /** 分组卡片之间的垂直间隔 */
    val sectionGap = 24.dp
}

// ============================================================
// 圆角
// ============================================================

object Radius {
    /** 8dp —— 小控件（chip、徽章） */
    val sm = 8.dp
    /** 12dp —— 分组卡片、输入框 */
    val md = 12.dp
    /** 16dp —— 大卡片 */
    val lg = 16.dp
    /** 20dp —— 对话框 */
    val xl = 20.dp
    /** 全圆角 —— 按钮、胶囊 */
    val pill = 999.dp
}

// ============================================================
// 行高
// ============================================================

object RowSize {
    /** 列表行最小高度。低于 48dp 会导致点按困难 */
    val minHeight = 52.dp
    /** 带副标题的列表行 */
    val twoLineMinHeight = 64.dp
    /** 底部导航栏高度 */
    val tabBar = 56.dp
}

// ============================================================
// 字号
// ============================================================

/**
 * 字号阶梯。用系统字体，不引入自定义字体文件。
 *
 * 名字沿用分组列表的直觉：大标题 / 标题 / 正文 / 脚注 / 说明。
 */
object AppType {
    /** 57sp —— Material displayLarge 槽位 */
    val displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = -0.25.sp
    )

    /** 45sp —— Material displayMedium 槽位 */
    val displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    )

    /** 36sp —— Material displaySmall 槽位 */
    val displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    )

    /** 34sp —— 页面大标题，出现在内容顶部 */
    val largeTitle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.4.sp
    )

    /** 22sp —— section 内的主要标题 */
    val title = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = -0.2.sp
    )

    /** 20sp —— 次级标题，如卡片主标题 */
    val title2 = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = -0.2.sp
    )

    /** 17sp 半粗 —— 列表行主文字 */
    val headline = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = -0.3.sp
    )

    /** 17sp —— 正文 */
    val body = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = -0.3.sp
    )

    /** 15sp —— 行内副标题 */
    val subhead = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = -0.2.sp
    )

    /** 13sp —— 脚注、section 标题 */
    val footnote = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = -0.1.sp
    )

    /** 12sp —— 说明文字 */
    val caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    /** 11sp —— 底部导航标签 */
    val caption2 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.1.sp
    )

    /** 17sp 半粗，用于数字与统计值 */
    val number = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = -0.4.sp
    )

    /** 28sp 半粗，用于大号统计数字 */
    val largeNumber = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = -0.6.sp
    )
}

// ============================================================
// 分类配色
// ============================================================

/**
 * 分类数据的回退配色。
 *
 * 用途：围栏自己带颜色（用户选的），但历史数据里可能有解析不了的值，
 * 这时按围栏 id 的哈希取一个稳定的颜色，保证相邻项能区分。
 *
 * **刻意不跟随主题**：它的职责是"把不同类别区分开"，不是表达品牌或语义。
 * 如果跟着强调色走，切换主题后所有围栏会一起变色，反而更难分辨。
 * 这组色在深浅两种背景上都有足够对比度。
 */
val categoricalColors: List<Color> = listOf(
    Color(0xFF6366F1), // 靛蓝
    Color(0xFFEC4899), // 品红
    Color(0xFF10B981), // 翠绿
    Color(0xFFF59E0B), // 琥珀
    Color(0xFFEF4444), // 红
    Color(0xFF8B5CF6), // 紫
    Color(0xFF06B6D4), // 青
    Color(0xFF84CC16)  // 黄绿
)
