package com.dropflow.android.ui.about

data class AboutUiState(
    val appVersion: String = "0.1.0-beta.13",
    val buildChannel: String = "Beta",
    val buildNumber: String = "20260805",
    val protocolVersion: String = "v1 (Binary TCP)",
    val isLocalOnly: Boolean = true,
    val isEncrypted: Boolean = true
)
