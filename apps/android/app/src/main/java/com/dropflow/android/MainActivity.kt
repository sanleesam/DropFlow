package com.dropflow.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.dropflow.android.ui.about.AboutViewModel
import com.dropflow.android.ui.history.HistoryViewModel
import com.dropflow.android.ui.home.HomeViewModel
import com.dropflow.android.ui.navigation.DropFlowNavHost
import com.dropflow.android.ui.settings.SettingsViewModel
import com.dropflow.android.ui.theme.DropFlowTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DropFlowApplication
        val userPrefsRepo = app.userPreferencesRepository

        val homeViewModel = HomeViewModel(applicationContext, userPrefsRepo)
        val historyViewModel = HistoryViewModel()
        val settingsViewModel = SettingsViewModel(userPrefsRepo)
        val aboutViewModel = AboutViewModel()

        setContent {
            DropFlowTheme {
                val navController = rememberNavController()
                DropFlowNavHost(
                    navController = navController,
                    homeViewModel = homeViewModel,
                    historyViewModel = historyViewModel,
                    settingsViewModel = settingsViewModel,
                    aboutViewModel = aboutViewModel
                )
            }
        }
    }
}
