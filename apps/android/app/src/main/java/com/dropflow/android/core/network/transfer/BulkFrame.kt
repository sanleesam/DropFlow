package com.dropflow.android.core.network.transfer

import java.io.InputStream
import java.io.OutputStream

/** Reusable DFP/1 bulk frames. The header and payload share one socket write. */
internal class BulkFrameWriter {
    val bytes = ByteArray(Protocol.HEADER_SIZE + 256 * 1024)
    val payloadCapacity get() = bytes.size - Protocol.HEADER_SIZE

    fun send(output: OutputStream, length: Int) {
        require(length in 1..payloadCapacity)
        FrameUtils.encodeHeader(bytes, FrameTag.DATA_CHUNK, length)
        output.write(bytes, 0, Protocol.HEADER_SIZE + length)
    }
}

internal class BulkFrameReader {
    private var bytes = ByteArray(0)

    fun read(input: InputStream, length: Int, remaining: Long): ByteArray {
        require(length in 1..Protocol.MAX_CHUNK_SIZE && length <= remaining) { "Invalid data chunk length" }
        if (bytes.size < length) bytes = ByteArray(length)
        FrameUtils.readPayloadInto(input, bytes, length)
        // Callers must use only [0, length) before the next read.
        return bytes
    }
}
