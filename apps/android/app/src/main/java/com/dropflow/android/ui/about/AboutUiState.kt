package com.dropflow.android.ui.about

import com.dropflow.android.BuildConfig
import com.dropflow.android.core.model.releaseLabel

data class AboutUiState(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildChannel: String = releaseLabel(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
)
