package com.dropflow.android.ui.theme

import androidx.compose.ui.graphics.Color

// Shared with desktop.css: Titanium surfaces, cool silver text and icy blue rims.
val BackgroundDark = Color(0xFF101317)
val SurfaceDark = Color(0xFF171C23)
val SurfaceGlass = Color(0xFF1A2028)
val SurfaceInset = Color(0xFF141A21)
val SurfaceGlassBorder = Color(0xFF343E49)
val IcyBlue = Color(0xFFB7DCFF)
val ProgressTrack = Color(0xFF343E4B)

// Accent Palettes matching Desktop ACCENT_COLOR_MAPS
val BlueAccentPrimary = Color(0xFF2F7CF6)
val BlueAccentStrong = Color(0xFF2563EB)
val BlueAccentHover = Color(0xFF75AAFF)
val BlueAccentSoft = Color(0x242F7CF6)

val EmeraldAccentPrimary = Color(0xFF10B981)
val VioletAccentPrimary = Color(0xFF8B5CF6)
val AmberAccentPrimary = Color(0xFFF59E0B)
val RoseAccentPrimary = Color(0xFFF43F5E)

val TextPrimary = Color(0xFFEEF3FC)
val TextSecondary = Color(0xFFB6C0CD)
val TextMuted = Color(0xFFA6B2C2)

val StatusOnline = Color(0xFF67D99C)
val StatusRecentlySeen = Color(0xFFEFBC6B)
val StatusOffline = TextMuted
val StatusFailed = Color(0xFFFF929B)

fun getAccentColor(name: String): Color {
    return when (name.lowercase()) {
        "emerald" -> EmeraldAccentPrimary
        "violet" -> VioletAccentPrimary
        "amber" -> AmberAccentPrimary
        "rose" -> RoseAccentPrimary
        else -> BlueAccentPrimary
    }
}
