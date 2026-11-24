package com.dropflow.android.ui.glass

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Liquid Glass lens model, ported from the desktop `displacement.ts`.
 *
 * The rim is a convex squircle bevel; each point is refracted with Snell's law
 * (single interface, orthogonal view ray) so light bends most where the surface
 * is steepest and the flat interior stays undistorted. The AGSL shader evaluates
 * the same curve per pixel; [peak] normalises it to 0…1.
 */
object LensProfile {
    const val GLASS_IOR = 1.5f
    const val SAMPLES = 128

    /** Convex squircle height: 0 at the edge, 1 where the rim meets the flat face. */
    fun rimHeight(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return (1f - (1f - x).pow(4)).pow(0.25f)
    }

    /** Un-normalised refraction magnitude at rim position t (0 = edge, 1 = flat). */
    fun refraction(t: Float, ior: Float = GLASS_IOR, samples: Int = SAMPLES): Float {
        val dt = 1f / samples
        val a = max(0f, t - dt)
        val b = min(1f, t + dt)
        val slope = (rimHeight(b) - rimHeight(a)) / (b - a)
        val incidence = atan(slope)
        val refracted = asin(sin(incidence) / ior)
        val value = rimHeight(t) * tan(incidence - refracted)
        return if (value.isFinite()) value else 0f
    }

    fun peak(ior: Float = GLASS_IOR, samples: Int = SAMPLES): Float =
        (0..samples).maxOf { refraction(it.toFloat() / samples, ior, samples) }

    /** Normalised profile across the rim, matching desktop `rimProfile`. */
    fun profile(ior: Float = GLASS_IOR, samples: Int = SAMPLES): FloatArray {
        val raw = FloatArray(samples + 1) { refraction(it.toFloat() / samples, ior, samples) }
        val top = raw.maxOrNull() ?: 0f
        if (top > 0f) for (i in raw.indices) raw[i] /= top
        return raw
    }

    /**
     * Displacement (in units of lens strength) for a point inside a rounded
     * rectangle of [width]×[height] with corner [radius] and rim width [bezel].
     * Points toward the centre so the rim magnifies what sits beneath it.
     */
    fun displacement(x: Float, y: Float, width: Float, height: Float, radius: Float, bezel: Float,
                     profile: FloatArray = profile()): Pair<Float, Float> {
        val hx = width / 2f
        val hy = height / 2f
        val r = radius.coerceIn(0f, min(hx, hy))
        val rim = bezel.coerceIn(1f, max(1f, min(hx, hy)))
        val px = x - hx
        val py = y - hy
        val qx = abs(px) - (hx - r)
        val qy = abs(py) - (hy - r)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        val outside = kotlin.math.hypot(ox, oy)
        val sdf = outside + min(max(qx, qy), 0f) - r
        if (sdf > 0f) return 0f to 0f
        val depth = -sdf
        if (depth >= rim) return 0f to 0f
        val (nx, ny) = when {
            outside > 0f -> (ox / outside) * sign(px) to (oy / outside) * sign(py)
            qx > qy -> sign(px) to 0f
            else -> 0f to sign(py)
        }
        val steps = profile.size - 1
        val m = profile[kotlin.math.round((depth / rim) * steps).toInt().coerceIn(0, steps)]
        return -nx * m to -ny * m
    }

    private fun sign(v: Float) = if (v > 0f) 1f else if (v < 0f) -1f else 0f
}
