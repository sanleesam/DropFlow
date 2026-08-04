package com.dropflow.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.dropflow.android.ui.navigation.Screen
import com.dropflow.android.ui.theme.BlueAccentPrimary
import com.dropflow.android.ui.theme.SurfaceDark
import com.dropflow.android.ui.theme.TextPrimary
import com.dropflow.android.ui.theme.TextSecondary

@Composable
fun BottomNavigationBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Screen.Home,
        Screen.History,
        Screen.Settings,
        Screen.About
    )

    NavigationBar(
        modifier = modifier,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        items.forEach { screen ->
            val selected = currentRoute == screen.route

            val (filledIcon, outlinedIcon) = when (screen) {
                Screen.Home -> Pair(Icons.Filled.Home, Icons.Outlined.Home)
                Screen.History -> Pair(Icons.Filled.History, Icons.Outlined.History)
                Screen.Settings -> Pair(Icons.Filled.Settings, Icons.Outlined.Settings)
                Screen.About -> Pair(Icons.Filled.Info, Icons.Outlined.Info)
            }

            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = if (selected) filledIcon else outlinedIcon,
                        contentDescription = screen.title
                    )
                },
                label = { Text(screen.title, fontSize = 11.sp) },
                selected = selected,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BlueAccentPrimary,
                    selectedTextColor = BlueAccentPrimary,
                    indicatorColor = BlueAccentPrimary.copy(alpha = 0.2f),
                    unselectedIconColor = TextSecondary,
                    unselectedTextColor = TextSecondary
                ),
                onClick = { onNavigate(screen) }
            )
        }
    }
}
