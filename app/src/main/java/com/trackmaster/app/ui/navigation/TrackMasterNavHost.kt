package com.trackmaster.app.ui.navigation

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trackmaster.app.CategoryManagerScreen
import com.trackmaster.app.DataManagementScreen
import com.trackmaster.app.FenceExportImportDialog
import com.trackmaster.app.SettingsPage
import com.trackmaster.app.StatisticsScreen
import com.trackmaster.app.StatsExportImportScreen
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.screens.*
import com.trackmaster.app.ui.theme.AppTheme
import com.trackmaster.app.ui.theme.ThemeManager
import com.trackmaster.app.ui.theme.backgroundColor
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackMasterNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val app: TrackMasterApplication = koinInject()
    val bgColor = backgroundColor()

    val startDestination by remember {
        derivedStateOf {
            if (app.appPreferences.isOnboardingCompleted) Screen.Home.route
            else Screen.Onboarding.route
        }
    }

    var currentRoute by remember { mutableStateOf<String?>(null) }
    var showStatisticsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            currentRoute = entry.destination.route
        }
    }

    val hideBottomNavRoutes = listOf(
        Screen.Onboarding.route,
        Screen.DataManagement.route,
        Screen.FenceExportImport.route,
        Screen.CategoryManager.route,
        Screen.Statistics.route,
        Screen.StatsExportImport.route
    )

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .background(color = bgColor),
            // 使用简单的淡入淡出，避免卡顿
            enterTransition = {
                fadeIn(animationSpec = tween(200))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(150))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(200))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(150))
            }
        ) {
            composable(
                route = Screen.Onboarding.route,
                enterTransition = {
                    fadeIn(animationSpec = tween(300))
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(200))
                }
            ) {
                OnboardingScreen(
                    onComplete = {
                        app.appPreferences.isOnboardingCompleted = true
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Onboarding.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Screen.Home.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                HomeScreen(
                    onNavigateToMap = { navController.navigate(Screen.Map.route) },
                    onNavigateToStatistics = { showStatisticsDialog = true }
                )
            }

            composable(
                route = Screen.Map.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                MapScreen(app = app)
            }

            composable(
                route = Screen.History.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                HistoryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Settings.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                SettingsPage(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenDataManagement = { navController.navigate(Screen.DataManagement.route) },
                    onOpenFenceExportImport = { navController.navigate(Screen.FenceExportImport.route) },
                    onOpenStatsExportImport = { navController.navigate(Screen.StatsExportImport.route) }
                )
            }

            composable(
                route = Screen.StatsExportImport.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                StatsExportImportScreen(
                    onDismiss = { navController.popBackStack() },
                    dataManager = app.dataManager,
                    stayDurationManager = app.stayDurationManager
                )
            }

            composable(
                route = Screen.Statistics.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                StatisticsScreen(
                    onDismiss = { navController.popBackStack() },
                    statisticsManager = app.statisticsManager
                )
            }

            composable(
                route = Screen.DataManagement.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                DataManagementScreen(
                    onDismiss = { navController.popBackStack() },
                    dataManager = app.dataManager
                )
            }

            composable(
                route = Screen.FenceExportImport.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                val context = LocalContext.current
                FenceExportImportDialog(
                    onDismiss = { navController.popBackStack() },
                    fences = app.geoFences.toList(),
                    onExport = { selectedFences ->
                        val json = app.exportFencesToJson(selectedFences)
                        if (json != null) {
                            try {
                                val fileName = "TrackMaster_Fences_${
                                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                }.json"
                                val file = File(context.getExternalFilesDir(null), fileName)
                                file.writeText(json)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "TrackMaster 围栏分享")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "分享围栏").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    onImport = { jsonContent ->
                        app.importFencesAndRefresh(jsonContent)
                    }
                )
            }

            composable(
                route = Screen.CategoryManager.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                CategoryManagerScreen(
                    onDismiss = { navController.popBackStack() }
                )
            }
        }

        // 使用简洁版底部导航栏
        if (currentRoute !in hideBottomNavRoutes) {
            var lastNavigationTime by remember { mutableStateOf(0L) }
            val navigationCooldown = 200L

            SimpleBottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNavigationTime < navigationCooldown) return@SimpleBottomNavBar
                    lastNavigationTime = currentTime

                    val currentDestRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentDestRoute != screen.route) {
                        navController.navigate(screen.route) {
                            launchSingleTop = true
                        }
                    }
                },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .padding(horizontal = 32.dp)
            )
        }

        // 统计对话框 - 作为浮动窗口显示
        if (showStatisticsDialog) {
            StatisticsScreen(
                onDismiss = { showStatisticsDialog = false },
                statisticsManager = app.statisticsManager
            )
        }
    }
}
