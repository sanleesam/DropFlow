package com.dropflow.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dropflow.android.ui.glass.GlassMode
import com.dropflow.android.ui.glass.GlassStyle
import com.dropflow.android.ui.glass.LocalGlassMode
import com.dropflow.android.ui.glass.rememberHighContrast

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Auto"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

private fun DropFlowColors.toMaterial() = if (isDark) darkColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentFill, onPrimaryContainer = accent,
    secondary = accent, onSecondary = onAccent, secondaryContainer = fill, onSecondaryContainer = label,
    background = window, onBackground = label, surface = surface, onSurface = label,
    surfaceVariant = fill.compositeOver(surface), onSurfaceVariant = label2,
    surfaceContainerLowest = window, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = glassSolid, surfaceContainerHighest = glassSolid,
    outline = fillStrong.compositeOver(surface), outlineVariant = separator.compositeOver(surface),
    error = red, onError = Color.White, scrim = Color.Black,
) else lightColorScheme(
    primary = accent, onPrimary = onAccent, primaryContainer = accentFill, onPrimaryContainer = accent,
    secondary = accent, onSecondary = onAccent, secondaryContainer = fill, onSecondaryContainer = label,
    background = window, onBackground = label, surface = surface, onSurface = label,
    surfaceVariant = fill.compositeOver(surface), onSurfaceVariant = label2,
    surfaceContainerLowest = Color.White, surfaceContainerLow = surface, surfaceContainer = surface,
    surfaceContainerHigh = glassSolid, surfaceContainerHighest = glassSolid,
    outline = fillStrong.compositeOver(surface), outlineVariant = separator.compositeOver(surface),
    error = red, onError = Color.White, scrim = Color.Black,
)

@Composable
fun DropFlowTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    glassStyle: GlassStyle = GlassStyle.LIQUID,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val systemAnimationsOff = rememberSystemAnimationsOff()
    val glassMode = GlassMode.resolve(glassStyle, Build.VERSION.SDK_INT, rememberHighContrast())
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalDropFlowColors provides colors,
        LocalMotionEnabled provides !(reduceMotion || systemAnimationsOff),
        LocalGlassMode provides glassMode,
    ) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = Typography, content = content)
    }
}
