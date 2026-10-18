package com.trackmaster.app.ui.theme

/**
 * TrackMaster 主题枚举
 *
 * 实际生效的主题配色由 ThemeManager.kt 中的 getTheme* 函数提供。
 */
enum class AppTheme(
    val displayName: String,
    val description: String
) {
    DARK_NIGHT("深邃暗夜", "珊瑚橙与深棕，沉浸深邃"),
    SOFT_EYE("柔和护眼", "蓝紫柔和，长时间舒适"),
    CLASSIC_RED("经典红黑", "红金配色，稳重大气"),
    MINIMAL_GRAPHITE("极简石墨", "黑白灰极简美学"),
    AURORA_GREEN("极光绿", "翡翠翠绿，自然清新"),
    DREAM_PURPLE("梦幻紫", "紫罗兰色，魔法浪漫"),
    SUNSET_ORANGE("落日橙", "暖橙粉色，温馨浪漫"),
    GLACIER_BLUE("冰川蓝", "冰蓝银色，清冷优雅"),
    CYBER_TEAL("赛博青", "电光青绿，赛博朋克"),
    MINT_FRESH("薄荷绿", "清爽薄荷，清新自然"),
    ROSE_GOLD("玫瑰金", "玫瑰粉金，优雅温柔"),
    SPACE_GRAY("深空灰", "银灰科技，简约质感"),
    LAVENDER("薰衣草紫", "薰衣草紫，文艺浪漫")
}
