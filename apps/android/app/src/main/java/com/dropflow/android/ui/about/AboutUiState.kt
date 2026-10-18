package com.dropflow.android.ui.about

import com.dropflow.android.BuildConfig

data class AboutUiState(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildChannel: String = "Beta"
)
