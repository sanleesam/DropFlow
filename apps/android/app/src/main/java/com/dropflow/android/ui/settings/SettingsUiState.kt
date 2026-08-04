package com.dropflow.android.ui.settings

data class SettingsUiState(
    val deviceName: String = "",
    val requireConfirmation: Boolean = true,
    val autoAcceptTrustedDevices: Boolean = true,
    val askBeforeOverwrite: Boolean = true,
    val autoOpenCompleted: Boolean = false,
    val accentColor: String = "blue",
    val downloadDirectory: String = "/storage/emulated/0/Download/DropFlow",
    val port: Int = 42100,
    val mdnsServiceType: String = "_dropflow._tcp.local.",
    val multicastGroup: String = "224.0.0.251"
)
