package com.dropflow.android.ui.settings

data class SettingsUiState(
    val deviceName: String = "",
    val requireConfirmation: Boolean = true,
    val autoAcceptTrustedDevices: Boolean = true,
    val askBeforeOverwrite: Boolean = true,
    val autoOpenCompleted: Boolean = false,
    val accentColor: String = "blue",
    val downloadDirectory: String = "/storage/emulated/0/Download/DropFlow"
)
