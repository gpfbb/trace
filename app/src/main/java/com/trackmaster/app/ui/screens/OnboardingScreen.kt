package com.trackmaster.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.trackmaster.app.AppPreferences
import com.trackmaster.app.ui.theme.DeepChocolate
import com.trackmaster.app.ui.theme.EspressoBlack
import com.trackmaster.app.ui.theme.GlassButton
import com.trackmaster.app.ui.theme.NeonCoral
import com.trackmaster.app.ui.theme.NeonAmber
import com.trackmaster.app.ui.theme.Success
import kotlinx.coroutines.launch

/**
 * 引导页 - 首次用户体验流程
 *
 * 3步引导：
 * 1. 欢迎 + 权限请求
 * 2. 地图功能介绍
 * 3. 快速开始
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(pageCount = { 3 })

    // 权限请求
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // 权限已授予
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(DeepChocolate, EspressoBlack)
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ============================================================
            // 跳过按钮
            // ============================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        prefs.isOnboardingCompleted = true
                        onComplete()
                    }
                ) {
                    Text(
                        text = "跳过",
                        color = Color.Gray
                    )
                }
            }

            // ============================================================
            // Pager 内容
            // ============================================================
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> OnboardingPage(
                        icon = Icons.Default.MyLocation,
                        title = "欢迎使用 TrackMaster",
                        description = "智能地理围栏追踪助手，精准记录您的每一次停留",
                        accentColor = NeonCoral
                    )
                    1 -> OnboardingPage(
                        icon = Icons.Default.Map,
                        title = "创建围栏",
                        description = "在地图上点击绘制圆形或多边形围栏，定义您关心的区域",
                        accentColor = NeonAmber
                    )
                    2 -> OnboardingPage(
                        icon = Icons.Default.Timeline,
                        title = "开始追踪",
                        description = "进入或离开围栏时自动记录，提供详细的停留时长统计",
                        accentColor = Success
                    )
                }
            }

            // ============================================================
            // 页面指示器
            // ============================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (pagerState.currentPage == index) 24.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index) NeonCoral
                                else NeonCoral.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            // ============================================================
            // 操作按钮
            // ============================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (pagerState.currentPage == 0) {
                    // 第一页：请求权限按钮
                    GlassButton(
                        onClick = {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasPermission) {
                                scope.launch { pagerState.animateScrollToPage(1) }
                            } else {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("授予定位权限", color = Color.White)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                    ) {
                        Text("稍后授权", color = Color.Gray)
                    }
                } else if (pagerState.currentPage < 2) {
                    // 第二页：下一步
                    GlassButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("下一步", color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White
                        )
                    }
                } else {
                    // 第三页：开始使用
                    GlassButton(
                        onClick = {
                            prefs.isOnboardingCompleted = true
                            onComplete()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始使用", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OnboardingPage(
    icon: ImageVector,
    title: String,
    description: String,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 图标
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(64.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // 标题
        Text(
            text = title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 描述
        Text(
            text = description,
            fontSize = 16.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}
