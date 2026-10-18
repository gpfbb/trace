package com.trackmaster.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Recommend
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trackmaster.app.AppLanguage
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.R
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.GroupedSection
import com.trackmaster.app.ui.design.IconTile
import com.trackmaster.app.ui.design.PrimaryButton
import com.trackmaster.app.ui.design.SecondaryButton
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette
import com.trackmaster.app.utils.LanguageManager
import kotlinx.coroutines.launch

/**
 * 首次启动引导：语言 → 地图 SDK → 欢迎 → 建围栏 → 开始使用。
 *
 * 相比旧实现的改动：
 * - 语言页选中后不再自动跳页：旧实现直接 `animateScrollToPage(1)`，
 *   用户来不及看到界面变成新语言。现在选中后停在原页，点"下一步"再走。
 * - 选中语言立即调用 [LanguageManager.applyLocale]，并通过
 *   [onLanguageChangeRequested] 通知宿主刷新 `LocalConfiguration`，
 *   界面文字当场切换。
 * - 权限状态用 `remember` + 结果回调刷新，不再在组合期直接查系统权限
 *   （组合期查询在权限被授予后不会自动重算）。
 * - 5 个页面合并成一个数据驱动的 [OnboardingSlide]，不再手写 5 个分支。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onLanguageChangeRequested: () -> Unit = {}
) {
    val palette = currentPalette
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext as com.trackmaster.app.TrackMasterApplication
    }.let { remember(it) { it.appPreferences } }
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })

    // 语言状态：用 rememberSaveable 保存枚举名，转屏不丢
    var selectedLanguageName by rememberSaveable {
        mutableStateOf(prefs.appLanguage.name)
    }
    val selectedLanguage = AppLanguage.valueOf(selectedLanguageName)

    // 权限状态用可变状态保存，并在页面回到前台时重新检查。
    //
    // 不能只靠 remember 的初始值：用户可能在系统设置里授予权限再回来，
    // 也可能在别的页面已经授予过。旧写法只在组合期查一次，
    // 授权后按钮仍然走"申请权限"分支，看起来像点了没反应。
    var hasLocationPermission by remember { mutableStateOf(checkLocationPermission(context)) }

    // 权限页出现时重新检查一次。
    //
    // 这是关键：HorizontalPager 只组合当前页，权限页被滑走时整页离开组合，
    // 它内部的 DisposableEffect / LaunchedEffect 也随之销毁。用户可能在
    // 别的页或系统设置里授予权限，回来时只能靠这里重新读一次。
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == PERMISSION_PAGE_INDEX) {
            hasLocationPermission = checkLocationPermission(context)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted || checkLocationPermission(context)
        // 无论系统回调说授没授，都以实际权限状态为准再决定是否前进
        if (checkLocationPermission(context)) {
            scope.launch { pagerState.animateScrollToPage(PERMISSION_PAGE_INDEX + 1) }
        }
    }

    fun goNext() {
        scope.launch {
            val next = (pagerState.currentPage + 1).coerceAtMost(ONBOARDING_PAGE_COUNT - 1)
            pagerState.animateScrollToPage(next)
        }
    }

    fun finish() {
        prefs.isOnboardingCompleted = true
        onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        // 顶部：跳过
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.sm, vertical = Space.xs),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Space.sm))
                    .clickable { finish() }
                    .padding(horizontal = Space.md, vertical = Space.sm)
            ) {
                Text(
                    text = stringResource(R.string.skip),
                    style = AppType.body,
                    color = palette.labelSecondary
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> LanguagePage(
                    selected = selectedLanguage,
                    onSelect = { language ->
                        selectedLanguageName = language.name
                        if (prefs.appLanguage != language) {
                            // applyLocale 会改 Resources，需要宿主刷 LocalConfiguration
                            LanguageManager.applyLocale(context, language, prefs.appLanguage)
                            onLanguageChangeRequested()
                        }
                    }
                )
                1 -> MapSdkPage()
                2 -> SlidePage(slide = SLIDES[0])   // 欢迎
                3 -> {                              // 建围栏 + 申请权限
                    SlidePageWithActions(slide = SLIDES[1]) {
                        PrimaryButton(
                            text = stringResource(R.string.grant_permission),
                            onClick = {
                                if (hasLocationPermission) {
                                    goNext()
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(Space.sm))
                        SecondaryButton(
                            text = stringResource(R.string.grant_later),
                            onClick = { goNext() }
                        )
                    }
                }
                else -> SlidePage(slide = SLIDES[2]) // 开始追踪
            }
        }

        // 页指示器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(ONBOARDING_PAGE_COUNT) { index ->
                val isCurrent = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = Space.xxs)
                        .size(if (isCurrent) 20.dp else 6.dp, 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrent) palette.accent else palette.labelDisabled
                        )
                )
            }
        }

        // 主按钮：按当前页决定行为
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.screenHorizontal)
                .navigationBarsPadding()
        ) {
            when (pagerState.currentPage) {
                0 -> PrimaryButton(
                    text = stringResource(R.string.next),
                    onClick = { goNext() }
                )
                3 -> Unit  // 按钮在页内，这里不渲染底部按钮
                ONBOARDING_PAGE_COUNT - 1 -> PrimaryButton(
                    text = stringResource(R.string.start_using),
                    onClick = { finish() }
                )
                else -> PrimaryButton(
                    text = stringResource(R.string.next),
                    onClick = { goNext() }
                )
            }
            Spacer(Modifier.height(Space.lg))
        }
    }
}

/** 是否已授予任意一种定位权限。 */
private fun checkLocationPermission(context: android.content.Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

// ============================================================
// 页面结构
// ============================================================

/**
 * 引导页共 5 页：
 * 0 语言选择 · 1 地图 SDK · 2 欢迎 · 3 建围栏（申请定位权限）· 4 开始追踪
 *
 * 权限申请放在第 3 页：那才是在真正要用定位之前。
 */
private const val ONBOARDING_PAGE_COUNT = 5

/** 权限申请所在页（"建围栏"页），用于回到该页时重新检查权限状态。 */
private const val PERMISSION_PAGE_INDEX = 3

// ============================================================
// 单页：圆角图标 + 标题 + 说明
// ============================================================

private data class OnboardingSlide(
    val icon: ImageVector,
    val titleRes: Int,
    val descriptionRes: Int
)

private val SLIDES = listOf(
    OnboardingSlide(
        icon = Icons.Default.MyLocation,
        titleRes = R.string.welcome_title,
        descriptionRes = R.string.welcome_desc
    ),
    OnboardingSlide(
        icon = Icons.Default.Map,
        titleRes = R.string.create_fence_title,
        descriptionRes = R.string.create_fence_desc
    ),
    OnboardingSlide(
        icon = Icons.Default.Timeline,
        titleRes = R.string.start_tracking_title,
        descriptionRes = R.string.start_tracking_desc
    )
)

@Composable
private fun SlidePage(slide: OnboardingSlide) {
    SlideLayout(
        icon = slide.icon,
        title = stringResource(slide.titleRes),
        description = stringResource(slide.descriptionRes)
    )
}

/** 带页内操作按钮的引导页，用于"建围栏"页的权限申请。 */
@Composable
private fun SlidePageWithActions(
    slide: OnboardingSlide,
    actions: @Composable () -> Unit
) {
    val palette = currentPalette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconTile(
            icon = slide.icon,
            containerColor = palette.accent,
            tint = palette.onAccent,
            size = 88.dp
        )
        Spacer(Modifier.height(Space.xxl))
        Text(
            text = stringResource(slide.titleRes),
            style = AppType.title,
            color = palette.label,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.md))
        Text(
            text = stringResource(slide.descriptionRes),
            style = AppType.body,
            color = palette.labelSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.xxl))
        actions()
    }
}

/** 所有引导页共用的版式。 */
@Composable
private fun SlideLayout(
    icon: ImageVector,
    title: String,
    description: String
) {
    val palette = currentPalette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconTile(
            icon = icon,
            containerColor = palette.accent,
            tint = palette.onAccent,
            size = 88.dp
        )
        Spacer(Modifier.height(Space.xxl))
        Text(
            text = title,
            style = AppType.title,
            color = palette.label,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.md))
        Text(
            text = description,
            style = AppType.body,
            color = palette.labelSecondary,
            textAlign = TextAlign.Center
        )
    }
}

// ============================================================
// 语言选择页
// ============================================================

@Composable
private fun LanguagePage(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit
) {
    val palette = currentPalette

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconTile(
            icon = Icons.Default.Language,
            containerColor = palette.accent,
            tint = palette.onAccent,
            size = 88.dp
        )
        Spacer(Modifier.height(Space.xxl))
        Text(
            text = stringResource(R.string.select_language_title),
            style = AppType.title,
            color = palette.label,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.md))
        Text(
            text = stringResource(R.string.select_language_desc),
            style = AppType.body,
            color = palette.labelSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.xxl))

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            listOf(
                AppLanguage.CHINESE to R.string.chinese,
                AppLanguage.ENGLISH to R.string.english,
                AppLanguage.MALAY to R.string.malay
            ).forEach { (language, labelRes) ->
                val isSelected = selected == language
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Space.md))
                        .background(palette.groupedCell)
                        .clickable { onSelect(language) }
                        .padding(horizontal = Space.lg, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(labelRes),
                        style = AppType.body,
                        color = palette.label,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 地图 SDK 说明页
// ============================================================

@Composable
private fun MapSdkPage() {
    val palette = currentPalette

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        IconTile(
            icon = Icons.Default.Recommend,
            containerColor = palette.warning,
            tint = palette.onAccent,
            size = 88.dp
        )
        Spacer(Modifier.height(Space.xxl))
        Text(
            text = stringResource(R.string.map_sdk_recommendation_title),
            style = AppType.title,
            color = palette.label,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.xl))

        GroupedSection {
            MapSdkRow(
                icon = Icons.Default.Map,
                iconTint = palette.success,
                text = stringResource(R.string.amap_recommendation)
            )
            com.trackmaster.app.ui.design.Divider()
            MapSdkRow(
                icon = Icons.Default.Public,
                iconTint = palette.accent,
                text = stringResource(R.string.google_recommendation)
            )
        }
    }
}

@Composable
private fun MapSdkRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    text: String
) {
    val palette = currentPalette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = text,
            style = AppType.subhead,
            color = palette.labelSecondary
        )
    }
}
