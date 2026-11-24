package com.dropflow.android.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensProfileTest {
    @Test
    fun rimHeightRunsFromEdgeToFlatFace() {
        assertEquals(0f, LensProfile.rimHeight(0f), 1e-6f)
        assertEquals(1f, LensProfile.rimHeight(1f), 1e-6f)
        assertEquals(1f, LensProfile.rimHeight(2f), 1e-6f)
        assertTrue(LensProfile.rimHeight(0.25f) < LensProfile.rimHeight(0.5f))
    }

    @Test
    fun profileIsNormalisedAndFlatInsideTheRim() {
        val profile = LensProfile.profile()
        assertEquals(LensProfile.SAMPLES + 1, profile.size)
        assertEquals(1f, profile.max(), 1e-6f)
        assertTrue(profile.all { it in 0f..1f })
        assertEquals(0f, profile.last(), 1e-3f)
    }

    @Test
    fun peakMatchesUnnormalisedMaximum() {
        val raw = (0..LensProfile.SAMPLES).map { LensProfile.refraction(it.toFloat() / LensProfile.SAMPLES) }
        assertEquals(raw.max(), LensProfile.peak(), 1e-6f)
    }

    @Test
    fun rimBendsLightTowardTheCentreAndLeavesTheFaceAlone() {
        val profile = LensProfile.profile()
        // Left rim, vertically centred: samples to the right (toward the centre).
        val (lx, ly) = LensProfile.displacement(4f, 50f, 200f, 100f, 30f, 20f, profile)
        assertTrue(lx > 0f)
        assertEquals(0f, ly, 1e-6f)
        // Bottom rim pushes upward.
        val (bx, by) = LensProfile.displacement(100f, 96f, 200f, 100f, 30f, 20f, profile)
        assertEquals(0f, bx, 1e-6f)
        assertTrue(by < 0f)
        // Flat interior and outside the shape are undistorted.
        assertEquals(0f to 0f, LensProfile.displacement(100f, 50f, 200f, 100f, 30f, 20f, profile))
        assertEquals(0f to 0f, LensProfile.displacement(1f, 1f, 200f, 100f, 30f, 20f, profile))
    }
}
