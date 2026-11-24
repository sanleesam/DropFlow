package com.dropflow.android.core.network.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class FrameWriteTest {
    private class CountingStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        override fun write(b: Int) { writes++; bytes.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { writes++; bytes.write(b, off, len) }
    }

    @Test
    fun controlFramesLeaveAsOneWriteWithUnchangedBytes() {
        val sessionId = "rx-6f1c2a"
        val payload = sessionId.toByteArray(Charsets.UTF_8)
        val legacy = ByteArrayOutputStream().also {
            FrameUtils.writeHeader(it, FrameTag.TRANSFER_COMPLETE, payload.size)
            it.write(payload)
        }.toByteArray()

        val out = CountingStream()
        FrameUtils.writeTransferComplete(out, sessionId)

        assertEquals(1, out.writes)
        assertArrayEquals(legacy, out.bytes.toByteArray())
        val header = FrameUtils.readHeader(out.bytes.toByteArray().inputStream())
        assertEquals(FrameTag.TRANSFER_COMPLETE, header.tag)
        assertEquals(payload.size, header.payloadLength)
    }

    @Test
    fun transferSlotSignalsBusyAndIdleOncePerTransfer() {
        var busy = 0
        var idle = 0
        val slot = TransferSlot(onBusy = { busy++ }, onIdle = { idle++ })
        assertTrue(slot.claim("a"))
        assertFalse(slot.claim("b"))
        slot.release("b")
        assertEquals(1 to 0, busy to idle)
        slot.release("a")
        slot.release("a")
        assertEquals(1 to 1, busy to idle)
    }
}

class TransferPerfTest {
    @Test
    fun summaryMatchesDesktopFormat() {
        assertEquals(
            "[Perf] send files=2 bytes=2097152 secs=0.500 mib_s=4.00 ms_per_file=250.0",
            TransferPerf.summary("send", 2, 2L * 1024 * 1024, 500_000_000L)
        )
        assertTrue(TransferPerf.summary("receive", 0, 0, 0).endsWith("mib_s=0.00 ms_per_file=0.0"))
    }
}
