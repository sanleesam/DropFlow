package com.dropflow.android.ui.navigation

sealed class Screen(val route: String, val title: String) {
    object Home : Screen("home", "Home")
    object History : Screen("history", "History")
    object Settings : Screen("settings", "Settings")
    object About : Screen("about", "About")
}
