package com.dropflow.android.ui.glass

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** What the user picked in Settings › Appearance › Glass. */
enum class GlassStyle(val id: String, val label: String) {
    LIQUID("liquid", "Liquid"),
    FROSTED("frosted", "Frosted"),
    SOLID("solid", "Solid");

    companion object {
        fun fromId(id: String?): GlassStyle = entries.firstOrNull { it.id == id } ?: LIQUID
    }
}

/**
 * How glass actually renders on this device.
 *
 * LENS  — Android 13+: blur, saturation and an AGSL refraction rim.
 * BLUR  — Android 12+: blur and saturation of the content beneath.
 * FROST — older Android: translucent tint and rim lighting, no backdrop sampling.
 * SOLID — Solid chosen, or high-contrast text / contrast is on: opaque surfaces.
 */
enum class GlassMode {
    LENS, BLUR, FROST, SOLID;

    val samplesBackdrop: Boolean get() = this == LENS || this == BLUR

    companion object {
        fun resolve(style: GlassStyle, sdkInt: Int, highContrast: Boolean): GlassMode = when {
            style == GlassStyle.SOLID || highContrast -> SOLID
            sdkInt < Build.VERSION_CODES.S -> FROST
            style == GlassStyle.FROSTED -> BLUR
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> LENS
            else -> BLUR
        }
    }
}

val LocalGlassMode = staticCompositionLocalOf { GlassMode.FROST }

/** Not in the public SDK; Settings › Accessibility › High contrast text writes it. */
private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"

private fun Context.wantsHighContrast(): Boolean {
    val text = runCatching { Settings.Secure.getInt(contentResolver, HIGH_TEXT_CONTRAST, 0) == 1 }.getOrDefault(false)
    val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.contrast?.let { it >= 0.5f } ?: false
    } else false
    return text || contrast
}

/** High-contrast text, or Android 14's medium/high colour contrast. */
@Composable
fun rememberHighContrast(): Boolean {
    val context = LocalContext.current
    var high by remember(context) { mutableStateOf(context.wantsHighContrast()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                high = context.wantsHighContrast()
            }
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(HIGH_TEXT_CONTRAST), false, observer)
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val contrastListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && uiMode != null) {
            UiModeManager.ContrastChangeListener { high = context.wantsHighContrast() }.also {
                uiMode.addContrastChangeListener(context.mainExecutor, it)
            }
        } else null
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && contrastListener != null) {
                uiMode?.removeContrastChangeListener(contrastListener)
            }
        }
    }
    return high
}
