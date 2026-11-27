package com.dropflow.android.ui.settings

import com.dropflow.android.ui.glass.GlassStyle
import com.dropflow.android.ui.theme.ThemeMode

data class SettingsUiState(
    val deviceName: String = "",
    val requireConfirmation: Boolean = true,
    val askBeforeOverwrite: Boolean = true,
    val autoOpenCompleted: Boolean = false,
    val accentColor: String = "blue",
    val reduceMotion: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val glassStyle: GlassStyle = GlassStyle.LIQUID,
    val directLink: Boolean = false,
    val directLinkLeavesWifi: Boolean = false,
    val downloadDirectory: String = "/storage/emulated/0/Download/DropFlow"
)
