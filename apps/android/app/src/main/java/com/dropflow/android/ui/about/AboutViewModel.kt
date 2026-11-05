package com.dropflow.android.ui.about

import androidx.lifecycle.ViewModel
import com.dropflow.android.BuildConfig
import com.dropflow.android.core.model.releaseLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AboutViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            appVersion = BuildConfig.VERSION_NAME,
            buildChannel = releaseLabel(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()
}
