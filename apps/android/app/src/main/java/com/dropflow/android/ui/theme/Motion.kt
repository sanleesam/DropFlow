package com.dropflow.android.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI

/**
 * Springs that match the desktop curves (SwiftUI `spring(duration: 0.5, bounce:)`).
 *
 * SwiftUI maps a perceptual duration d to stiffness (2π / d)² and bounce b to
 * damping ratio 1 − b, so the same physics drive both platforms.
 */
object Springs {
    private const val PERCEPTUAL_DURATION = 0.5f
    val STIFFNESS: Float = ((2 * PI / PERCEPTUAL_DURATION) * (2 * PI / PERCEPTUAL_DURATION)).toFloat()

    const val SMOOTH = 1.0f
    const val SNAPPY = 0.85f
    const val BOUNCY = 0.72f

    fun <T> smooth(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(SMOOTH, STIFFNESS, visibilityThreshold)
    fun <T> snappy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(SNAPPY, STIFFNESS, visibilityThreshold)
    fun <T> bouncy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> = spring(BOUNCY, STIFFNESS, visibilityThreshold)
}

/** False when the user turned motion off here or set Android's animator scale to 0. */
val LocalMotionEnabled = staticCompositionLocalOf { true }

object Motion {
    val enabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalMotionEnabled.current

    @Composable @ReadOnlyComposable
    fun <T> smooth(): FiniteAnimationSpec<T> = if (enabled) Springs.smooth() else snap()

    @Composable @ReadOnlyComposable
    fun <T> snappy(): FiniteAnimationSpec<T> = if (enabled) Springs.snappy() else snap()

    @Composable @ReadOnlyComposable
    fun <T> bouncy(): FiniteAnimationSpec<T> = if (enabled) Springs.bouncy() else snap()
}

fun <T> AnimationSpec<T>.orSnap(enabled: Boolean): AnimationSpec<T> = if (enabled) this else snap()

private fun Context.animatorScale(): Float =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Tracks Settings › Developer options › Animator duration scale (and accessibility "Remove animations"). */
@Composable
fun rememberSystemAnimationsOff(): Boolean {
    val context = LocalContext.current
    var off by remember(context) { mutableStateOf(context.animatorScale() == 0f) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                off = context.animatorScale() == 0f
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return off
}
