package com.trackmaster.app.ui.navigation

import com.trackmaster.app.R

/**
 * 导航路由定义
 *
 * 定义应用中所有屏幕的路由，使用密封类确保类型安全。
 */
sealed class Screen(val route: String) {

    /**
     * 闪屏/启动页
     */
    object Splash : Screen("splash")

    /**
     * 引导页（首次用户）
     */
    object Onboarding : Screen("onboarding")

    /**
     * 首页仪表盘
     */
    object Home : Screen("home")

    /**
     * 地图页
     */
    object Map : Screen("map")

    /**
     * 历史记录页
     */
    object History : Screen("history")

    /**
     * 设置页
     */
    object Settings : Screen("settings")

    /**
     * 统计详情页
     */
    object Statistics : Screen("statistics")

    /**
     * 围栏详情页
     */
    object FenceDetail : Screen("fence/{fenceId}") {
        fun createRoute(fenceId: String) = "fence/$fenceId"
    }

    /**
     * 分类管理页
     */
    object CategoryManager : Screen("category_manager")

    /**
     * 数据导入导出页（统一入口：围栏 + 统计 + 日志清理）
     */
    object DataImportExport : Screen("data_import_export")
}

/**
 * 底部导航项目
 */
enum class BottomNavItem(
    val screen: Screen,
    @androidx.annotation.StringRes val titleRes: Int,
    val iconName: String
) {
    HOME(Screen.Home, R.string.nav_home, "home"),
    MAP(Screen.Map, R.string.nav_map, "map"),
    HISTORY(Screen.History, R.string.nav_history, "history"),
    SETTINGS(Screen.Settings, R.string.nav_settings, "settings")
}
