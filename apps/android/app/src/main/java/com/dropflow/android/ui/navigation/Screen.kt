package com.dropflow.android.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "Home")
    object Devices : Screen("devices", "Devices")
    object History : Screen("history", "Transfers")
    object Settings : Screen("settings", "Settings")
    object About : Screen("about", "About")
}
