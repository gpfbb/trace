package com.trackmaster.app.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trackmaster.app.CategoryManagerScreen
import com.trackmaster.app.DataManagementScreen
import com.trackmaster.app.FenceExportImportDialog
import com.trackmaster.app.SettingsPage
import com.trackmaster.app.SettingsScreen
import com.trackmaster.app.StatisticsScreen
import com.trackmaster.app.StatsExportImportScreen
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.screens.*
import com.trackmaster.app.ui.theme.DeepChocolate
import com.trackmaster.app.ui.theme.EspressoBlack
import org.koin.compose.koinInject

@Composable
fun TrackMasterNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val app: TrackMasterApplication = koinInject()

    val startDestination by remember {
        derivedStateOf {
            if (app.appPreferences.isOnboardingCompleted) Screen.Home.route
            else Screen.Onboarding.route
        }
    }

    var currentRoute by remember { mutableStateOf<String?>(null) }

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
        Screen.Statistics.route
    )

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(DeepChocolate, EspressoBlack)
                    )
                )
        ) {
            composable(
                route = Screen.Onboarding.route,
                enterTransition = { fadeIn(tween(400)) },
                exitTransition = { fadeOut(tween(300)) }
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
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                HomeScreen(
                    onNavigateToMap = { navController.navigate(Screen.Map.route) },
                    onNavigateToStatistics = { navController.navigate(Screen.Statistics.route) }
                )
            }

            composable(
                route = Screen.Map.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(200)) }
            ) {
                MapScreen(app = app)
            }

            composable(
                route = Screen.History.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                HistoryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Settings.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
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
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                StatsExportImportScreen(
                    onDismiss = { navController.popBackStack() },
                    dataManager = app.dataManager,
                    stayDurationManager = app.stayDurationManager
                )
            }

            composable(
                route = Screen.Statistics.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                StatisticsScreen(
                    onDismiss = { navController.popBackStack() },
                    statisticsManager = app.statisticsManager
                )
            }

            composable(
                route = Screen.DataManagement.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                DataManagementScreen(
                    onDismiss = { navController.popBackStack() },
                    dataManager = app.dataManager
                )
            }

            composable(
                route = Screen.FenceExportImport.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                FenceExportImportDialog(
                    onDismiss = { navController.popBackStack() },
                    fences = app.geoFences.toList(),
                    onExport = { },
                    onImport = { }
                )
            }

            composable(
                route = Screen.CategoryManager.route,
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(250)) }
            ) {
                CategoryManagerScreen(
                    onDismiss = { navController.popBackStack() }
                )
            }
        }

        if (currentRoute !in hideBottomNavRoutes) {
            var lastNavigationTime by remember { mutableStateOf(0L) }
            val navigationCooldown = 400L

            BottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastNavigationTime < navigationCooldown) return@BottomNavBar
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
                    .padding(bottom = 16.dp)
            )
        }
    }
}
