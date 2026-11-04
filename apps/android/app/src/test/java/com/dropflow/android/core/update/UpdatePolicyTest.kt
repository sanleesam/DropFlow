package com.dropflow.android.core.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun connectivityFailuresHaveReadableRecoveryMessages() {
        assertTrue(updateErrorMessage(java.net.UnknownHostException("github.com")).contains("Check your internet connection"))
        assertTrue(updateErrorMessage(java.net.SocketTimeoutException()).contains("too long"))
        assertTrue(updateErrorMessage(javax.net.ssl.SSLException("certificate")).contains("secure connection"))
        assertEquals("Update checksum did not match", updateErrorMessage(IllegalArgumentException("Update checksum did not match")))
    }
    private val body = "update fixture".toByteArray()
    private val valid = AndroidUpdateManifest(1, "0.2.0-alpha.2", 19, "com.dropflow.android",
        "https://github.com/sanleesam/DropFlow/releases/download/android-v0.2.0-alpha.2/DropFlow-android-0.2.0-alpha.2.apk",
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }, body.size.toLong())

    @Test fun orderingUsesSemverAndRequiresIncreasingInstallNumber() {
        assertTrue(valid.validate(AndroidUpdateChannel.ALPHA).isNewerThan("0.1.0-beta.17", 17))
        assertFalse(valid.isNewerThan("0.2.0-alpha.2", 18))
        assertFalse(valid.isNewerThan("0.2.0-alpha.1", 19))
        assertTrue(SemanticVersion.parse("0.2.0-beta.10") > SemanticVersion.parse("0.2.0-beta.9"))
        assertTrue(SemanticVersion.parse("0.2.0") > SemanticVersion.parse("0.2.0-rc.9"))
        for (bad in listOf("v0.2.0", "0.02.0", "0.2.0-alpha.01", "0.2.0+foo")) assertThrows(Exception::class.java) { SemanticVersion.parse(bad) }
    }
    @Test fun rejectsWrongChannelPackageUrlSizeAndChecksum() {
        assertThrows(Exception::class.java) { valid.validate(AndroidUpdateChannel.BETA) }
        for (manifest in listOf(valid.copy(schemaVersion = 2), valid.copy(packageId = "other.app"),
            valid.copy(url = valid.url.replace("github.com", "evil.example")), valid.copy(size = MAX_APK_SIZE + 1),
            valid.copy(sha256 = "bad"), valid.copy(versionCode = 0))) {
            assertThrows(Exception::class.java) { manifest.validate(AndroidUpdateChannel.ALPHA) }
        }
        assertFalse(isTrustedUpdateRedirect(URI("http://github.com/file")))
        assertFalse(isTrustedUpdateRedirect(URI("https://github.com.evil.example/file")))
        assertFalse(isTrustedUpdateRedirect(URI("https://user@github.com/file")))
        assertTrue(isTrustedUpdateRedirect(URI("https://release-assets.githubusercontent.com/file")))
    }
    @Test fun downloadRequiresExactLengthAndDigest() {
        val output = ByteArrayOutputStream()
        copyVerifiedUpdate(ByteArrayInputStream(body), output, valid) {}
        assertArrayEquals(body, output.toByteArray())
        for (data in listOf(body.copyOf(body.size - 1), body + byteArrayOf(1), ByteArray(body.size))) {
            assertThrows(Exception::class.java) { copyVerifiedUpdate(ByteArrayInputStream(data), ByteArrayOutputStream(), valid) {} }
        }
    }
}
