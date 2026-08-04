package com.dropflow.android.ui.theme

import androidx.compose.ui.graphics.Color

val BackgroundDark = Color(0xFF0B0C0E)
val SurfaceDark = Color(0xFF14161A)
val SurfaceGlass = Color(0x991E2026)
val SurfaceGlassBorder = Color(0x1AFFFFFF)

// Accent Palettes matching Desktop ACCENT_COLOR_MAPS
val BlueAccentPrimary = Color(0xFF2563EB)
val BlueAccentHover = Color(0xFF3B82F6)
val BlueAccentSoft = Color(0x1F2563EB)

val EmeraldAccentPrimary = Color(0xFF10B981)
val VioletAccentPrimary = Color(0xFF8B5CF6)
val AmberAccentPrimary = Color(0xFFF59E0B)
val RoseAccentPrimary = Color(0xFFF43F5E)

val TextPrimary = Color(0xFFF5F5F5)
val TextSecondary = Color(0xFFA3A3A3)
val TextMuted = Color(0xFF737373)

val StatusOnline = Color(0xFF34D399)
val StatusRecentlySeen = Color(0xFFFBBF24)
val StatusOffline = Color(0xFF737373)
val StatusFailed = Color(0xFFF87171)

fun getAccentColor(name: String): Color {
    return when (name.lowercase()) {
        "emerald" -> EmeraldAccentPrimary
        "violet" -> VioletAccentPrimary
        "amber" -> AmberAccentPrimary
        "rose" -> RoseAccentPrimary
        else -> BlueAccentPrimary
    }
}
