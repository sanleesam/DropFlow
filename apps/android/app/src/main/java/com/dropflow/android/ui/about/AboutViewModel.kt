package com.dropflow.android.ui.about

import androidx.lifecycle.ViewModel
import com.dropflow.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AboutViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            appVersion = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "0.1.0" },
            buildChannel = try { BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() } } catch (_: Throwable) { "Development" }
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()
}
