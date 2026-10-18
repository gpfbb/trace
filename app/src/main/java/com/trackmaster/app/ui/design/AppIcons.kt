package com.trackmaster.app.ui.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 图标语义表。
 *
 * ## 为什么要这一层
 *
 * 之前每个屏幕各自 `Icons.Filled.X` / `Icons.Default.X`，结果是同一类东西
 * 在不同页里风格不一致：设置页用实心 Folder，地图页用实心 Palette，
 * 底部导航又用线框。视觉重量参差，看起来像几个人分别做的。
 *
 * 这里按**用途**而不是按外形命名，风格规则集中在一处：
 *
 * - **导航/标签**：[navSelected] 实心、[navUnselected] 线框。
 *   实心线框的切换比只换颜色更容易一眼分辨当前在哪。
 * - **列表行左侧的小图标**：统一线框。线框在列表里不抢视线，
 *   和 iOS 设置页的做法一致。
 * - **动作按钮内的图标**：实心（加号、删除、关闭）。这些是"点一下会发生
 *   什么"的强提示，实心更明确。
 * - **状态指示**：实心（LocationOn 表示在围栏内）。状态要醒目。
 *
 * 注意：Material 没有为每个图标都提供 Outlined 变体（比如 `Add` 只有
 * 实心版）。这种情况下仍然用实心，不要为了"统一"去自造路径。
 */
object AppIcons {

    // ------------------------------------------------------------
    // 底部导航：选中实心 / 未选中线框
    // ------------------------------------------------------------

    val navHomeSelected: ImageVector = Icons.Filled.Home
    // 注意：Material 1.6.3 没有提供 Outlined.Home，首页的未选中态只能用实心。
    // 为了和另外三个 tab 视觉一致，这里用同一个实心图标，靠颜色区分选中。
    val navHomeUnselected: ImageVector = Icons.Filled.Home

    val navMapSelected: ImageVector = Icons.Filled.Map
    val navMapUnselected: ImageVector = Icons.Outlined.Map

    val navHistorySelected: ImageVector = Icons.AutoMirrored.Filled.List
    val navHistoryUnselected: ImageVector = Icons.AutoMirrored.Outlined.List

    val navSettingsSelected: ImageVector = Icons.Filled.Settings
    val navSettingsUnselected: ImageVector = Icons.Outlined.Settings

    // ------------------------------------------------------------
    // 列表行图标（统一线框）
    // ------------------------------------------------------------

    val folder: ImageVector = Icons.Outlined.Folder
    val tune: ImageVector = Icons.Outlined.Tune
    val language: ImageVector = Icons.Outlined.Language
    val palette: ImageVector = Icons.Outlined.Palette
    val mapService: ImageVector = Icons.Outlined.Map
    val settings: ImageVector = Icons.Outlined.Settings
    val hierarchy: ImageVector = Icons.Outlined.AccountTree
    val category: ImageVector = Icons.Outlined.Category
    val merge: ImageVector = Icons.AutoMirrored.Outlined.MergeType
    val star: ImageVector = Icons.Outlined.Star

    /** 设置页的可展开分组：展开时朝上，收起时朝下。 */
    fun expandChevron(expanded: Boolean): ImageVector =
        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore

    // ------------------------------------------------------------
    // 动作按钮（实心）
    // ------------------------------------------------------------

    val add: ImageVector = Icons.Filled.Add
    val close: ImageVector = Icons.Filled.Close
    val delete: ImageVector = Icons.Filled.Delete
    val edit: ImageVector = Icons.Filled.Edit
    val check: ImageVector = Icons.Filled.Check
    val checkCircle: ImageVector = Icons.Filled.CheckCircle
    val back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val chevronRight: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight

    // ------------------------------------------------------------
    // 状态指示（实心）
    // ------------------------------------------------------------

    val insideFence: ImageVector = Icons.Filled.LocationOn
    val outsideFence: ImageVector = Icons.Filled.LocationOff
    val myLocation: ImageVector = Icons.Filled.MyLocation

    // ------------------------------------------------------------
    // 地图控件
    // ------------------------------------------------------------

    val mapNormal: ImageVector = Icons.Filled.Map
    val mapSatellite: ImageVector = Icons.Filled.Satellite
    val mapNight: ImageVector = Icons.Filled.NightsStay
    val opacity: ImageVector = Icons.Filled.Opacity

    // 线框版留给需要"轻"的场景（如未选中的图层）
    val circleOutline: ImageVector = Icons.Outlined.Circle
    val opacityOutline: ImageVector = Icons.Outlined.Opacity
    val checkCircleOutline: ImageVector = Icons.Outlined.CheckCircle
}
