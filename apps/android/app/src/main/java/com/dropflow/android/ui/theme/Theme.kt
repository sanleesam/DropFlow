package com.dropflow.android.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalReducedMotion = staticCompositionLocalOf { false }

private val DarkColorScheme = darkColorScheme(
    primary = BlueAccentHover,
    secondary = IcyBlue,
    primaryContainer = BlueAccentSoft,
    onPrimaryContainer = IcyBlue,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = BackgroundDark,
    surfaceVariant = SurfaceInset,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceGlassBorder,
    error = StatusFailed,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun DropFlowTheme(
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(LocalReducedMotion provides reduceMotion) {
      MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
      )
    }
}
