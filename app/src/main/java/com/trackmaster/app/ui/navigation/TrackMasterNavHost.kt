package com.trackmaster.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trackmaster.app.CategoryManagerScreen
import com.trackmaster.app.DataManagementScreen
import com.trackmaster.app.SettingsPage
import com.trackmaster.app.StatisticsScreen
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.screens.*
import com.trackmaster.app.ui.theme.backgroundColor
import com.trackmaster.app.viewmodel.HomeViewModel
import com.trackmaster.app.viewmodel.MapViewModel
import org.koin.compose.koinInject

@Composable
fun TrackMasterNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val app: TrackMasterApplication = koinInject()
    val mapViewModel: MapViewModel = koinInject()
    val homeViewModel: HomeViewModel = koinInject()
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
        Screen.DataImportExport.route,
        Screen.CategoryManager.route,
        Screen.Statistics.route
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
                    onNavigateToStatistics = { showStatisticsDialog = true },
                    homeViewModel = homeViewModel
                )
            }

            composable(
                route = Screen.Map.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                MapScreen(app = app, mapViewModel = mapViewModel)
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
                    onOpenDataImportExport = { navController.navigate(Screen.DataImportExport.route) }
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
                route = Screen.DataImportExport.route,
                enterTransition = { fadeIn(animationSpec = tween(200)) },
                exitTransition = { fadeOut(animationSpec = tween(150)) }
            ) {
                DataManagementScreen(
                    onDismiss = { navController.popBackStack() },
                    dataManager = app.dataManager,
                    app = app
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

        // 底部标签栏：贴底通栏，内容区通过各自的 contentPadding 让出空间
        if (currentRoute !in hideBottomNavRoutes) {
            SimpleBottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    val currentDestRoute = navController.currentBackStackEntry?.destination?.route
                    if (currentDestRoute != screen.route) {
                        navController.navigate(screen.route) {
                            launchSingleTop = true
                            // 四个 tab 之间切换不留返回栈，避免返回键在 tab 间来回跳
                            popUpTo(Screen.Home.route) { saveState = true }
                            restoreState = true
                        }
                    }
                },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
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
