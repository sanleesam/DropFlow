package com.dropflow.android.core.network.transfer

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

enum class FrameTag(val code: Byte) {
    TRANSFER_REQUEST(0x01),
    TRANSFER_ACCEPT(0x02),
    TRANSFER_REJECT(0x03),
    FILE_HEADER(0x04),
    DATA_CHUNK(0x05),
    TRANSFER_COMPLETE(0x06),
    TRANSFER_CANCEL(0x07),
    TRANSFER_ACK(0x08),
    FILE_COMPLETE(0x09),
    HEARTBEAT(0x0A),
    ERROR(0x0B),
    // DFP/1.1: a request whose manifest does not fit in 64 KB.
    TRANSFER_REQUEST_LARGE(0x0C),
    // DFP/1.1: what the receiver already holds from an interrupted attempt.
    RESUME_STATE(0x0D),
    // Direct link: the host's network, where a listening joiner waits, and the outcome.
    DIRECT_OFFER(0x0E),
    DIRECT_JOINED(0x0F),
    DIRECT_RESULT(0x10);

    companion object {
        fun fromCode(code: Byte): FrameTag {
            return values().firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown frame tag code: 0x${String.format("%02X", code)}")
        }
    }
}

data class FrameHeader(
    val tag: FrameTag,
    val payloadLength: Int
)

object FrameUtils {

    /**
     * Writes header + payload as one write so a control frame never leaves as
     * two TCP segments. Same bytes on the wire as header-then-payload.
     */
    fun writeFrame(outputStream: OutputStream, tag: FrameTag, payload: ByteArray) {
        outputStream.write(encodeFrame(tag, payload))
        outputStream.flush()
    }

    internal fun encodeFrame(tag: FrameTag, payload: ByteArray): ByteArray {
        val frame = ByteArray(Protocol.HEADER_SIZE + payload.size)
        encodeHeader(frame, tag, payload.size)
        payload.copyInto(frame, Protocol.HEADER_SIZE)
        return frame
    }

    fun writeHeader(outputStream: OutputStream, tag: FrameTag, payloadLength: Int) {
        val header = ByteArray(Protocol.HEADER_SIZE)
        encodeHeader(header, tag, payloadLength)
        outputStream.write(header)
    }

    internal fun encodeHeader(buffer: ByteArray, tag: FrameTag, payloadLength: Int, offset: Int = 0) {
        require(offset >= 0 && buffer.size - offset >= Protocol.HEADER_SIZE)
        require(payloadLength in 0..maxPayloadSize(tag)) {
            "Invalid ${tag.name} payload size: $payloadLength"
        }
        Protocol.MAGIC_BYTES.copyInto(buffer, offset)
        buffer[offset + 4] = Protocol.PROTOCOL_VERSION
        buffer[offset + 5] = tag.code
        buffer[offset + 6] = (payloadLength ushr 24).toByte()
        buffer[offset + 7] = (payloadLength ushr 16).toByte()
        buffer[offset + 8] = (payloadLength ushr 8).toByte()
        buffer[offset + 9] = payloadLength.toByte()
    }

    fun readHeader(inputStream: InputStream): FrameHeader {
        val headerBytes = ByteArray(Protocol.HEADER_SIZE)
        var bytesRead = 0
        while (bytesRead < Protocol.HEADER_SIZE) {
            val read = inputStream.read(headerBytes, bytesRead, Protocol.HEADER_SIZE - bytesRead)
            if (read == -1) {
                throw java.io.EOFException("End of stream reached while reading frame header")
            }
            bytesRead += read
        }

        // Validate magic bytes
        for (i in 0 until 4) {
            if (headerBytes[i] != Protocol.MAGIC_BYTES[i]) {
                throw IllegalArgumentException("Invalid protocol magic bytes: ${headerBytes.take(4)}")
            }
        }

        val version = headerBytes[4]
        if (version != Protocol.PROTOCOL_VERSION) {
            throw IllegalArgumentException("Unsupported protocol version: $version")
        }

        val tag = FrameTag.fromCode(headerBytes[5])
        val payloadLen = ByteBuffer.wrap(headerBytes, 6, 4).apply { order(ByteOrder.BIG_ENDIAN) }.int

        if (payloadLen !in 0..maxPayloadSize(tag)) {
            throw IllegalArgumentException("Invalid ${tag.name} payload size: $payloadLen")
        }

        return FrameHeader(tag = tag, payloadLength = payloadLen)
    }

    /**
     * Memory grows with the bytes that actually arrive, so a peer cannot
     * reserve a large buffer by only claiming a length.
     */
    fun readPayload(inputStream: InputStream, length: Int): ByteArray {
        require(length >= 0)
        var payload = ByteArray(minOf(length, Protocol.MAX_METADATA_SIZE))
        var bytesRead = 0
        while (bytesRead < length) {
            if (bytesRead == payload.size) {
                payload = payload.copyOf(minOf(length.toLong(), payload.size * 2L).toInt())
            }
            val read = inputStream.read(payload, bytesRead, payload.size - bytesRead)
            if (read <= 0) {
                throw java.io.EOFException("End of stream reached while reading payload")
            }
            bytesRead += read
        }
        return payload
    }

    internal fun readPayloadInto(inputStream: InputStream, payload: ByteArray, length: Int) {
        require(length in 0..payload.size)
        var bytesRead = 0
        while (bytesRead < length) {
            val read = inputStream.read(payload, bytesRead, length - bytesRead)
            if (read <= 0) {
                throw java.io.EOFException("End of stream reached while reading payload")
            }
            bytesRead += read
        }
    }

    /**
     * A manifest over 64 KB uses the large frame, which only DFP/1.1 peers
     * accept. Returns true when the large frame was used.
     */
    fun writeTransferRequest(outputStream: OutputStream, payload: TransferRequestPayload): Boolean =
        writeTransferRequest(outputStream, encodeTransferRequest(payload))

    internal fun encodeTransferRequest(payload: TransferRequestPayload): ByteArray {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        require(jsonBytes.size <= Protocol.MAX_LARGE_METADATA_SIZE) {
            "Too many files for one transfer. Send them in smaller groups."
        }
        return jsonBytes
    }

    internal fun writeTransferRequest(outputStream: OutputStream, jsonBytes: ByteArray): Boolean {
        val large = jsonBytes.size > Protocol.MAX_METADATA_SIZE
        writeFrame(outputStream, if (large) FrameTag.TRANSFER_REQUEST_LARGE else FrameTag.TRANSFER_REQUEST, jsonBytes)
        return large
    }

    fun writeHeartbeat(outputStream: OutputStream, message: String) {
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.HEARTBEAT, messageBytes)
    }

    fun writeTransferAccept(outputStream: OutputStream, payload: TransferAcceptPayload = TransferAcceptPayload()) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.TRANSFER_ACCEPT, jsonBytes)
    }

    fun writeTransferReject(outputStream: OutputStream, payload: TransferRejectPayload = TransferRejectPayload()) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.TRANSFER_REJECT, jsonBytes)
    }

    fun writeFileHeader(outputStream: OutputStream, payload: FileHeaderPayload) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.FILE_HEADER, jsonBytes)
    }

    fun writeTransferAck(outputStream: OutputStream, payload: TransferAckPayload = TransferAckPayload()) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.TRANSFER_ACK, jsonBytes)
    }

    fun writeFileComplete(outputStream: OutputStream, payload: FileCompletePayload) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.FILE_COMPLETE, jsonBytes)
    }

    fun writeTransferComplete(outputStream: OutputStream, sessionId: String) {
        val sessionBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.TRANSFER_COMPLETE, sessionBytes)
    }

    fun writeTransferCancel(outputStream: OutputStream, sessionId: String) {
        val sessionBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        writeFrame(outputStream, FrameTag.TRANSFER_CANCEL, sessionBytes)
    }

    internal fun maxPayloadSize(tag: FrameTag): Int = when (tag) {
        FrameTag.DATA_CHUNK -> Protocol.MAX_CHUNK_SIZE
        FrameTag.TRANSFER_REQUEST_LARGE, FrameTag.RESUME_STATE -> Protocol.MAX_LARGE_METADATA_SIZE
        else -> Protocol.MAX_METADATA_SIZE
    }
}
