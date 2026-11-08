package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test

class BulkFrameTest {
    @Test fun dataHeaderMatchesDfp1AndShortWriteDoesNotLeakStaleBytes() {
        val writer = BulkFrameWriter()
        writer.bytes.fill(0x7F)
        val offset = Protocol.HEADER_SIZE
        byteArrayOf(1, 2, 3).copyInto(writer.bytes, offset)
        val wire = ByteArrayOutputStream()
        writer.send(wire, 3)
        assertArrayEquals(byteArrayOf(0x44, 0x46, 0x50, 0x31, 1, 5, 0, 0, 0, 3, 1, 2, 3), wire.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { writer.send(wire, 0) }
    }

    @Test fun receiverReusesBufferAndRejectsOversizedOrIncompleteFrames() {
        val reader = BulkFrameReader()
        val first = reader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, 3)
        val second = reader.read(ByteArrayInputStream(byteArrayOf(4, 5)), 2, 2)
        assertSame(first, second)
        assertArrayEquals(byteArrayOf(4, 5), second.copyOf(2))
        assertThrows(IllegalArgumentException::class.java) {
            reader.read(ByteArrayInputStream(byteArrayOf()), Protocol.MAX_CHUNK_SIZE + 1, Long.MAX_VALUE)
        }
        assertThrows(EOFException::class.java) { reader.read(ByteArrayInputStream(byteArrayOf(1)), 2, 2) }
    }
}
