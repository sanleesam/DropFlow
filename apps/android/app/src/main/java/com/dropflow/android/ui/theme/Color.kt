package com.dropflow.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Apple system palette, shared with the desktop `theme.css`.
 *
 * Content sits on plain [window] / [surface] colours; only the navigation and
 * control layer uses the glass tokens.
 */
@Immutable
data class DropFlowColors(
    val isDark: Boolean,
    val accent: Color,
    val accentFill: Color,
    val onAccent: Color,
    val green: Color,
    val greenFill: Color,
    val red: Color,
    val redFill: Color,
    val orange: Color,
    val orangeFill: Color,
    val label: Color,
    val label2: Color,
    val label3: Color,
    val separator: Color,
    val fill: Color,
    val fill2: Color,
    val fillStrong: Color,
    val window: Color,
    val surface: Color,
    val glassTint: Color,
    val glassTintLens: Color,
    val glassFrost: Color,
    val glassSolid: Color,
    val rimHi: Color,
    val rimMid: Color,
    val rimLo: Color,
    val sheen: Float,
    val shadow: Color,
    val scrim: Color,
)

val LightColors = DropFlowColors(
    isDark = false,
    accent = Color(0xFF007AFF),
    accentFill = Color(0x24007AFF),
    onAccent = Color.White,
    green = Color(0xFF28A745),
    greenFill = Color(0x2934C759),
    red = Color(0xFFE5352B),
    redFill = Color(0x1FFF3B30),
    orange = Color(0xFFC76A00),
    orangeFill = Color(0x29FF9500),
    label = Color(0xDB000000),
    label2 = Color(0xBD3C3C43),
    label3 = Color(0x6B3C3C43),
    separator = Color(0x213C3C43),
    fill = Color(0x21787880),
    fill2 = Color(0x14787880),
    fillStrong = Color(0x33787880),
    window = Color(0xFFF2F2F5),
    surface = Color(0xFFFFFFFF),
    glassTint = Color(0x80FFFFFF),
    glassTintLens = Color(0x42FFFFFF),
    glassFrost = Color(0xE6F7F7F9),
    glassSolid = Color(0xFFF7F7F9),
    rimHi = Color(0xF2FFFFFF),
    rimMid = Color(0x80FFFFFF),
    rimLo = Color(0x1FFFFFFF),
    sheen = 0.32f,
    shadow = Color(0xFF000000),
    scrim = Color(0x33000000),
)

val DarkColors = DropFlowColors(
    isDark = true,
    accent = Color(0xFF0A84FF),
    accentFill = Color(0x3D0A84FF),
    onAccent = Color.White,
    green = Color(0xFF30D158),
    greenFill = Color(0x2E30D158),
    red = Color(0xFFFF6961),
    redFill = Color(0x2EFF453A),
    orange = Color(0xFFFFB340),
    orangeFill = Color(0x2EFF9F0A),
    label = Color(0xEBFFFFFF),
    label2 = Color(0xA8EBEBF5),
    label3 = Color(0x5CEBEBF5),
    separator = Color(0x1AFFFFFF),
    fill = Color(0x42787880),
    fill2 = Color(0x24787880),
    fillStrong = Color(0x5C787880),
    window = Color(0xFF121214),
    surface = Color(0xFF1F1F22),
    glassTint = Color(0x752C2C30),
    glassTintLens = Color(0x4D242428),
    glassFrost = Color(0xEB2A2A2D),
    glassSolid = Color(0xFF2A2A2D),
    rimHi = Color(0x6BFFFFFF),
    rimMid = Color(0x29FFFFFF),
    rimLo = Color(0x0AFFFFFF),
    sheen = 0.09f,
    shadow = Color(0xFF000000),
    scrim = Color(0x66000000),
)

val LocalDropFlowColors = staticCompositionLocalOf { LightColors }

/** The palette for the current light/dark appearance. */
val Palette: DropFlowColors
    @Composable @ReadOnlyComposable get() = LocalDropFlowColors.current
