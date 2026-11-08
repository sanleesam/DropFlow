package com.dropflow.android.nearby

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyP2pRouteTest {
    private fun ip(vararg octets: Int) = ByteArray(octets.size) { octets[it].toByte() }

    @Test fun handlesSubnetBoundaryAndUnsignedOctets() {
        val a = ip(192, 168, 137, 66)
        assertTrue(samePrefix(a, ip(192, 168, 137, 64), 26))
        assertFalse(samePrefix(a, ip(192, 168, 137, 128), 26))
        assertFalse(samePrefix(a, ip(192, 168, 138, 66), 24))
        assertTrue(samePrefix(a, a, 32))
    }

    @Test fun rejectsDefaultRoutesAndNonIpv4() {
        val a = ip(192, 168, 137, 66)
        assertFalse(samePrefix(a, a, 0))
        assertFalse(samePrefix(a, a, 33))
        assertFalse(samePrefix(a, ByteArray(16), 24))
    }
}
