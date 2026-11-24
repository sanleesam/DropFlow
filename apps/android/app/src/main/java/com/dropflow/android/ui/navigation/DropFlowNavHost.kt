package com.dropflow.android.ui.navigation

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.ui.about.AboutScreen
import com.dropflow.android.ui.about.AboutViewModel
import com.dropflow.android.ui.components.ChromeState
import com.dropflow.android.ui.components.GlassRail
import com.dropflow.android.ui.components.GlassTabBar
import com.dropflow.android.ui.components.GlassToast
import com.dropflow.android.ui.components.IconCircleButton
import com.dropflow.android.ui.components.IncomingTransferSheet
import com.dropflow.android.ui.components.LocalChrome
import com.dropflow.android.ui.components.SendDock
import com.dropflow.android.ui.components.TabItem
import com.dropflow.android.ui.components.TopBarHeight
import com.dropflow.android.ui.components.TopChrome
import com.dropflow.android.ui.components.TransferDock
import com.dropflow.android.ui.devices.DevicesScreen
import com.dropflow.android.ui.glass.Glass
import com.dropflow.android.ui.glass.GlassBackdrop
import com.dropflow.android.ui.glass.GlassBackdropHost
import com.dropflow.android.ui.glass.GlassMode
import com.dropflow.android.ui.glass.GlassSize
import com.dropflow.android.ui.glass.LocalGlassBackdrop
import com.dropflow.android.ui.glass.LocalGlassMode
import com.dropflow.android.ui.history.HistoryScreen
import com.dropflow.android.ui.history.HistoryViewModel
import com.dropflow.android.ui.home.HomeScreen
import com.dropflow.android.ui.home.HomeViewModel
import com.dropflow.android.ui.settings.SettingsScreen
import com.dropflow.android.ui.settings.SettingsViewModel
import com.dropflow.android.ui.theme.Motion
import com.dropflow.android.ui.theme.Palette
import com.dropflow.android.ui.theme.Springs
import com.dropflow.android.ui.updates.UpdatesScreen
import com.dropflow.android.ui.updates.UpdatesViewModel

private val destinations = listOf(Screen.Home, Screen.Devices, Screen.History, Screen.Settings)
private val tabs = listOf(
    TabItem(Screen.Home.title, Icons.Outlined.Home, Icons.Rounded.Home),
    TabItem(Screen.Devices.title, Icons.Outlined.Devices, Icons.Rounded.Devices),
    TabItem(Screen.History.title, Icons.Rounded.SwapVert, Icons.Rounded.SwapVert),
    TabItem(Screen.Settings.title, Icons.Outlined.Settings, Icons.Rounded.Settings),
)

private fun selectedTab(route: String?): Int = when (route) {
    Screen.About.route, Screen.Updates.route -> destinations.indexOf(Screen.Settings)
    else -> destinations.indexOfFirst { it.route == route }
}

private fun titleFor(route: String?): String =
    listOf(Screen.Home, Screen.Devices, Screen.History, Screen.Settings, Screen.About, Screen.Updates)
        .firstOrNull { it.route == route }?.title ?: Screen.Home.title

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropFlowNavHost(navController: NavHostController, homeViewModel: HomeViewModel,
    historyViewModel: HistoryViewModel, settingsViewModel: SettingsViewModel,
    aboutViewModel: AboutViewModel, updatesViewModel: UpdatesViewModel, modifier: Modifier = Modifier) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Screen.Home.route
    val state by homeViewModel.uiState.collectAsState()
    val history by historyViewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var displayedResultId by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = Palette
    val motion = Motion.enabled
    val chrome = remember { ChromeState() }
    val backdrop = remember { GlassBackdrop() }
    val density = LocalDensity.current
    val navigate: (Screen) -> Unit = { screen ->
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    LaunchedEffect(state.transferResultId) {
        val resultId = state.transferResultId
        if (resultId != null && resultId != displayedResultId) {
            displayedResultId = resultId
            state.transferResultMessage?.let { snackbar.showSnackbar(it, withDismissAction = true) }
        }
    }
    LaunchedEffect(route) { chrome.scrolled = false }

    val selected = state.discoveredDevices.firstOrNull {
        it.id == state.selectedDeviceId && it.status == DeviceStatus.ONLINE
    }
    // Same rules the Home and Devices pages used for their send cards.
    val sendBusy = when (route) {
        Screen.Devices.route -> state.outgoingTransferActive || state.activeProgress != null || state.activeSessions.isNotEmpty()
        else -> state.outgoingTransferActive || state.activeProgress != null || state.pendingTransferRequest != null
    }
    val showSendDock = route == Screen.Home.route || route == Screen.Devices.route
    val sheetOpen = state.pendingTransferRequest != null
    val glassMode = LocalGlassMode.current
    val pageBlur by animateFloatAsState(if (sheetOpen && glassMode != GlassMode.SOLID) 1f else 0f,
        Motion.smooth(), label = "sheetBlur")
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalChrome provides chrome) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            // Keep compact-height landscape controls reachable; use a rail on larger windows.
            val expanded = maxWidth >= 720.dp && maxHeight >= 480.dp
            chrome.top = statusTop + TopBarHeight
            chrome.start = if (expanded) 104.dp else 0.dp
            if (expanded) chrome.bottom = navBottom + 12.dp

            GlassBackdropHost(backdrop, colors.window, Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pageBlur > 0f) {
                        val r = 18.dp.toPx() * pageBlur
                        renderEffect = BlurEffect(r, r, TileMode.Clamp)
                    }
                }) {
                // Page content never sees the backdrop, so glass can't end up sampling itself.
                CompositionLocalProvider(LocalGlassBackdrop provides null) {
                    Box(Modifier.fillMaxSize().padding(start = chrome.start), contentAlignment = Alignment.TopCenter) {
                        NavHost(navController, startDestination = Screen.Home.route,
                            modifier = Modifier.widthIn(max = if (expanded) 760.dp else 680.dp).fillMaxSize(),
                            enterTransition = { fadeIn(if (motion) tween(180) else snap()) },
                            exitTransition = { fadeOut(if (motion) tween(140) else snap()) }) {
                            composable(Screen.Home.route) {
                                HomeScreen(homeViewModel, history.allHistoryItems,
                                    onViewDevices = { navigate(Screen.Devices) },
                                    onViewTransfers = { navigate(Screen.History) })
                            }
                            composable(Screen.Devices.route) { DevicesScreen(homeViewModel) }
                            composable(Screen.History.route) { HistoryScreen(historyViewModel) }
                            composable(Screen.Settings.route) {
                                SettingsScreen(settingsViewModel,
                                    onAbout = { navController.navigate(Screen.About.route) { launchSingleTop = true } },
                                    onUpdates = { navController.navigate(Screen.Updates.route) { launchSingleTop = true } })
                            }
                            composable(Screen.About.route) { AboutScreen(aboutViewModel) }
                            composable(Screen.Updates.route) { UpdatesScreen(updatesViewModel) }
                        }
                    }
                }
            }

            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                val back = route in listOf(Screen.About.route, Screen.Updates.route)
                val refresh = route == Screen.Home.route || route == Screen.Devices.route
                TopChrome(titleFor(route), chrome.scrolled, Modifier.padding(start = chrome.start),
                    navigation = if (back) {
                        {
                            Glass(size = GlassSize.Small, elevation = 6.dp) {
                                IconCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Settings",
                                    { navController.popBackStack(); Unit }, size = 44.dp)
                            }
                        }
                    } else null,
                    actions = if (refresh) {
                        { IconCircleButton(Icons.Rounded.Refresh, "Rescan devices", homeViewModel::refreshDevices, size = 40.dp) }
                    } else null)

                if (expanded) {
                    GlassRail(tabs, selectedTab(route), { navigate(destinations[it]) },
                        Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
                }

                Column(Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = chrome.start)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 10.dp)
                    .onSizeChanged { if (!expanded) chrome.bottom = with(density) { it.height.toDp() } + navBottom + 10.dp },
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SnackbarHost(snackbar) { data ->
                        GlassToast(data.visuals.message, state.isTransferResultError, data::dismiss, Modifier.fillMaxWidth())
                    }
                    val dock = when {
                        state.activeProgress != null -> DockKind.Transfer
                        showSendDock -> DockKind.Send
                        else -> DockKind.None
                    }
                    AnimatedContent(dock,
                        transitionSpec = {
                            if (motion) (fadeIn(Springs.smooth()) + scaleIn(Springs.bouncy(), initialScale = 0.92f))
                                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.96f))
                                .using(SizeTransform(clip = false) { _, _ -> Springs.snappy() })
                            else (fadeIn(snap()) togetherWith fadeOut(snap()))
                        }, label = "dock") { kind ->
                        when (kind) {
                            DockKind.Transfer -> state.activeProgress?.let { TransferDock(it, homeViewModel::cancelActiveTransfer) }
                            DockKind.Send -> SendDock(selected, sendBusy, homeViewModel::sendFiles)
                            DockKind.None -> Box(Modifier.fillMaxWidth())
                        }
                    }
                    AnimatedVisibility(!expanded) {
                        GlassTabBar(tabs, selectedTab(route), { navigate(destinations[it]) })
                    }
                }
            }
        }
    }
    // Requests stay actionable across every destination.
    state.pendingTransferRequest?.let { request ->
        key(request.sessionId) {
            IncomingTransferSheet(request,
                onAccept = { homeViewModel.acceptTransfer(request) },
                onDecline = { homeViewModel.declineTransfer(request) })
        }
    }
}

private enum class DockKind { Send, Transfer, None }
