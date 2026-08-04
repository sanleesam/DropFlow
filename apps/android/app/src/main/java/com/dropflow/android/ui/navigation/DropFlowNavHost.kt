package com.dropflow.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dropflow.android.ui.about.AboutScreen
import com.dropflow.android.ui.about.AboutViewModel
import com.dropflow.android.ui.components.BottomNavigationBar
import com.dropflow.android.ui.components.TopAppBar
import com.dropflow.android.ui.history.HistoryScreen
import com.dropflow.android.ui.history.HistoryViewModel
import com.dropflow.android.ui.home.HomeScreen
import com.dropflow.android.ui.home.HomeViewModel
import com.dropflow.android.ui.settings.SettingsScreen
import com.dropflow.android.ui.settings.SettingsViewModel
import com.dropflow.android.ui.theme.BackgroundDark

@Composable
fun DropFlowNavHost(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel,
    aboutViewModel: AboutViewModel,
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentScreenTitle = when (currentRoute) {
        Screen.History.route -> Screen.History.title
        Screen.Settings.route -> Screen.Settings.title
        Screen.About.route -> Screen.About.title
        else -> Screen.Home.title
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = currentScreenTitle,
                onRefresh = if (currentRoute == Screen.Home.route) {
                    { homeViewModel.refreshDevices() }
                } else null
            )
        },
        bottomBar = {
            BottomNavigationBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        },
        containerColor = BackgroundDark
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(viewModel = homeViewModel)
            }
            composable(Screen.History.route) {
                HistoryScreen(viewModel = historyViewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = settingsViewModel)
            }
            composable(Screen.About.route) {
                AboutScreen(viewModel = aboutViewModel)
            }
        }
    }
}
