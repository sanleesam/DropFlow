package com.dropflow.android.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassModeTest {
    @Test
    fun liquidUsesTheLensFromAndroid13() {
        assertEquals(GlassMode.LENS, GlassMode.resolve(GlassStyle.LIQUID, 33, highContrast = false))
        assertEquals(GlassMode.LENS, GlassMode.resolve(GlassStyle.LIQUID, 37, highContrast = false))
    }

    @Test
    fun olderAndroidFallsBackToBlurThenFrost() {
        assertEquals(GlassMode.BLUR, GlassMode.resolve(GlassStyle.LIQUID, 31, highContrast = false))
        assertEquals(GlassMode.BLUR, GlassMode.resolve(GlassStyle.LIQUID, 32, highContrast = false))
        assertEquals(GlassMode.FROST, GlassMode.resolve(GlassStyle.LIQUID, 30, highContrast = false))
        assertEquals(GlassMode.FROST, GlassMode.resolve(GlassStyle.FROSTED, 26, highContrast = false))
    }

    @Test
    fun frostedNeverRefracts() {
        assertEquals(GlassMode.BLUR, GlassMode.resolve(GlassStyle.FROSTED, 34, highContrast = false))
    }

    @Test
    fun solidAndHighContrastAreOpaque() {
        assertEquals(GlassMode.SOLID, GlassMode.resolve(GlassStyle.SOLID, 34, highContrast = false))
        assertEquals(GlassMode.SOLID, GlassMode.resolve(GlassStyle.LIQUID, 34, highContrast = true))
        assertEquals(GlassMode.SOLID, GlassMode.resolve(GlassStyle.FROSTED, 26, highContrast = true))
        assertFalse(GlassMode.SOLID.samplesBackdrop)
        assertFalse(GlassMode.FROST.samplesBackdrop)
        assertTrue(GlassMode.LENS.samplesBackdrop)
    }

    @Test
    fun unknownPreferenceFallsBackToLiquid() {
        assertEquals(GlassStyle.LIQUID, GlassStyle.fromId(null))
        assertEquals(GlassStyle.LIQUID, GlassStyle.fromId("glossy"))
        assertEquals(GlassStyle.SOLID, GlassStyle.fromId("solid"))
    }
}
