package com.dropflow.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.dropflow.android.core.network.transfer.StorageManager
import com.dropflow.android.ui.about.AboutViewModel
import com.dropflow.android.ui.history.HistoryViewModel
import com.dropflow.android.ui.home.HomeViewModel
import com.dropflow.android.ui.navigation.DropFlowNavHost
import com.dropflow.android.ui.settings.SettingsViewModel
import com.dropflow.android.ui.theme.DropFlowTheme

class MainActivity : ComponentActivity() {

    // ViewModelProvider-backed instances survive configuration changes (rotation,
    // dark-mode toggles), so discovery registration and history are not recreated
    // every time the Activity is rebuilt.
    private val homeViewModel: HomeViewModel by viewModels {
        val app = application as DropFlowApplication
        HomeViewModel.factory(
            applicationContext,
            app.userPreferencesRepository,
            app.transferEngine
        )
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModel.factory((application as DropFlowApplication).transferEngine)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory((application as DropFlowApplication).userPreferencesRepository)
    }

    private val aboutViewModel: AboutViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Legacy devices (Android 9 and below) need WRITE_EXTERNAL_STORAGE granted
        // before incoming transfers can be saved into public Downloads.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

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
