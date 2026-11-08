package com.dropflow.android.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.Collections

class NearbyLabWireTest {
    private val androidDigest = "bee90c7a41e5acd47a8f107974fd8b8a8b89fb52828d6b700696f34bdd926ee9"
    private val windowsDigest = "a8dbf0591ada7c90e6ac885810b6dd3d2f59d5641fba32322ccb3fc6135e1610"

    private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun windowsOutput(receipt: ByteArray, corrupt: Boolean = false): SequenceInputStream {
        val block = NearbyLabWire.pattern(0xa2)
        if (corrupt) block[7] = 0
        return SequenceInputStream(Collections.enumeration(
            List(256) { ByteArrayInputStream(block) } + ByteArrayInputStream(receipt),
        ))
    }

    private class Transcript : OutputStream() {
        var written = 0
        val payload = MessageDigest.getInstance("SHA-256")
        var magic = ""
        var receipt = ByteArray(0)
        override fun write(value: Int) = error("Expected block writes")
        override fun write(bytes: ByteArray) {
            when (bytes.size) {
                8 -> magic = bytes.decodeToString()
                32 -> receipt = bytes.copyOf()
                64 * 1024 -> payload.update(bytes)
                else -> error("Unexpected field length")
            }
            written += bytes.size
        }
    }

    @Test fun exchangesTheSameWireBytesAsTheWindowsHost() {
        val transcript = Transcript()
        NearbyLabWire.exchange(windowsOutput(androidDigest.bytes()), transcript)
        assertEquals("DFNLAB01", transcript.magic)
        assertEquals(8 + 16 * 1024 * 1024 + 32, transcript.written)
        assertEquals(androidDigest, transcript.payload.digest().hex())
        assertEquals(windowsDigest, transcript.receipt.hex())
    }

    @Test fun rejectsCorruptionWithoutReturningAReceipt() {
        val transcript = Transcript()
        assertThrows(IllegalStateException::class.java) {
            NearbyLabWire.exchange(windowsOutput(androidDigest.bytes(), corrupt = true), transcript)
        }
        assertEquals(8 + 16 * 1024 * 1024, transcript.written)
    }

    @Test fun rejectsWrongFinalReceipt() {
        assertThrows(IllegalStateException::class.java) {
            NearbyLabWire.exchange(windowsOutput(ByteArray(32)), Transcript())
        }
    }
}
