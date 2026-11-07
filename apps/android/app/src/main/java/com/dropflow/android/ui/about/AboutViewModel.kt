package com.dropflow.android.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dropflow.android.core.model.InstalledRelease
import com.dropflow.android.core.model.releaseLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AboutViewModel(release: InstalledRelease) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            appVersion = release.versionName,
            buildChannel = releaseLabel(release.versionName, release.debuggable)
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    companion object {
        fun factory(release: InstalledRelease): ViewModelProvider.Factory = viewModelFactory {
            initializer { AboutViewModel(release) }
        }
    }
}
