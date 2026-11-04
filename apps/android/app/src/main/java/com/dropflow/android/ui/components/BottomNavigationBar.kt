package com.dropflow.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.*

private val destinations = listOf(Screen.Home, Screen.Devices, Screen.History, Screen.Settings)
private fun navigationIcon(screen: Screen): ImageVector = when (screen) {
    Screen.Home -> Icons.Default.Home
    Screen.Devices -> Icons.Default.Devices
    Screen.History -> Icons.Default.SwapVert
    Screen.Settings, Screen.About, Screen.Updates -> Icons.Default.Settings
}
private fun isSelected(screen: Screen, route: String?) =
    route == screen.route || (screen == Screen.Settings && route in listOf(Screen.About.route, Screen.Updates.route))

@Composable
fun BottomNavigationBar(currentRoute: String?, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier, containerColor = SurfaceDark, contentColor = TextPrimary) {
        destinations.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(navigationIcon(screen), null) },
                label = { Text(screen.title, fontSize = 11.sp) },
                selected = isSelected(screen, currentRoute),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = IcyBlue, selectedTextColor = IcyBlue,
                    indicatorColor = BlueAccentSoft,
                    unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary),
                onClick = { onNavigate(screen) })
        }
    }
}

@Composable
fun DesktopNavigationRail(currentRoute: String?, onNavigate: (Screen) -> Unit) {
    NavigationRail(Modifier.width(104.dp).fillMaxHeight(), containerColor = SurfaceDark,
        contentColor = TextPrimary) {
        Spacer(Modifier.height(20.dp))
        destinations.forEach { screen ->
            NavigationRailItem(
                icon = { Icon(navigationIcon(screen), null) },
                label = { Text(screen.title, fontSize = 12.sp) },
                selected = isSelected(screen, currentRoute),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = IcyBlue, selectedTextColor = IcyBlue,
                    indicatorColor = BlueAccentSoft,
                    unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary),
                onClick = { onNavigate(screen) })
            Spacer(Modifier.height(12.dp))
        }
    }
}
