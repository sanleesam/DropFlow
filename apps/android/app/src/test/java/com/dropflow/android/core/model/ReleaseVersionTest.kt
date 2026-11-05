package com.dropflow.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionTest {
    @Test fun labelsFollowTheInstalledVersion() {
        assertEquals("Alpha", releaseLabel("0.2.0-alpha.1", false))
        assertEquals("Alpha · Debug build", releaseLabel("0.2.0-alpha.1", true))
        assertEquals("Beta", releaseLabel("0.2.0-beta.1", false))
        assertEquals("Release", releaseLabel("0.2.0", false))
    }
}
