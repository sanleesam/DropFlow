package com.dropflow.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class SpringsTest {
    @Test
    fun dampingMatchesDesktopCurves() {
        assertEquals(1.0f, Springs.SMOOTH)
        assertEquals(0.85f, Springs.SNAPPY)
        assertEquals(0.72f, Springs.BOUNCY)
    }

    @Test
    fun stiffnessMatchesSwiftUiHalfSecondSpring() {
        // SwiftUI: stiffness = (2π / duration)², duration 0.5 s.
        assertEquals(157.91f, Springs.STIFFNESS, 0.01f)
    }
}
