package com.dropflow.android.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.dropflow.android.ui.about.*
import com.dropflow.android.ui.components.*
import com.dropflow.android.ui.history.*
import com.dropflow.android.ui.home.*
import com.dropflow.android.ui.settings.*
import com.dropflow.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropFlowNavHost(navController: NavHostController, homeViewModel: HomeViewModel,
    historyViewModel: HistoryViewModel, settingsViewModel: SettingsViewModel,
    aboutViewModel: AboutViewModel, modifier: Modifier = Modifier) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Screen.Home.route
    val state by homeViewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var displayedResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val transitionDuration = if (LocalReducedMotion.current) 0 else 160
    LaunchedEffect(state.transferResultId) {
        val resultId = state.transferResultId
        if (resultId != null && resultId != displayedResultId) {
            displayedResultId = resultId
            state.transferResultMessage?.let { snackbar.showSnackbar(it, withDismissAction = true) }
        }
    }
    TitaniumBackground(modifier.fillMaxSize()) {
        Scaffold(containerColor = Color.Transparent,
            topBar = {
                Column {
                    TopAppBar(onRefresh = if (route == Screen.Home.route) homeViewModel::refreshDevices else null)
                    state.activeProgress?.let {
                        ActiveTransferCard(it, homeViewModel::cancelActiveTransfer,
                            Modifier.widthIn(max = 680.dp).align(Alignment.CenterHorizontally).padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
            },
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(data, containerColor = SurfaceGlass, contentColor = TextPrimary,
                        dismissActionContentColor = IcyBlue, actionColor = IcyBlue,
                        shape = RoundedCornerShape(12.dp))
                }
            },
            bottomBar = {
                BottomNavigationBar(route, onNavigate = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                NavHost(navController, startDestination = Screen.Home.route,
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                    enterTransition = { fadeIn(tween(transitionDuration)) },
                    exitTransition = { fadeOut(tween(transitionDuration)) }) {
                    composable(Screen.Home.route) { HomeScreen(homeViewModel) }
                    composable(Screen.History.route) { HistoryScreen(historyViewModel) }
                    composable(Screen.Settings.route) { SettingsScreen(settingsViewModel) }
                    composable(Screen.About.route) { AboutScreen(aboutViewModel) }
                }
            }
        }
    }
    // Requests must remain actionable while browsing any screen.
    state.pendingTransferRequest?.let { request ->
        key(request.sessionId) {
            IncomingTransferBottomSheet(request,
                onAccept = homeViewModel::acceptTransfer, onDecline = { homeViewModel.declineTransfer() })
        }
    }
}
