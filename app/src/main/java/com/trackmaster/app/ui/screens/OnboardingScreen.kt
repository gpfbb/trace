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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

    val pagerState = rememberPagerState(pageCount = { SLIDES.size })

    // 语言状态：用 rememberSaveable 保存枚举名，转屏不丢
    var selectedLanguageName by rememberSaveable {
        mutableStateOf(prefs.appLanguage.name)
    }
    val selectedLanguage = AppLanguage.valueOf(selectedLanguageName)

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch { pagerState.animateScrollToPage(2) }
        }
    }

    fun goNext() {
        scope.launch {
            val next = (pagerState.currentPage + 1).coerceAtMost(SLIDES.lastIndex)
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
                else -> SlidePage(slide = SLIDES[page])
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
            repeat(SLIDES.size) { index ->
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
                2 -> {
                    // 权限页：可以直接申请，也可以稍后再说
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
                    SecondaryButton(
                        text = stringResource(R.string.grant_later),
                        onClick = { goNext() }
                    )
                }
                SLIDES.lastIndex -> PrimaryButton(
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
