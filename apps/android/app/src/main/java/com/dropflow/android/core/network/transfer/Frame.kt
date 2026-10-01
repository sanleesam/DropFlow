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
    ERROR(0x0B);

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

    fun writeHeader(outputStream: OutputStream, tag: FrameTag, payloadLength: Int) {
        require(payloadLength in 0..maxPayloadSize(tag)) {
            "Invalid ${tag.name} payload size: $payloadLength"
        }
        val buffer = ByteBuffer.allocate(Protocol.HEADER_SIZE).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(Protocol.MAGIC_BYTES)
            put(Protocol.PROTOCOL_VERSION)
            put(tag.code)
            putInt(payloadLength)
        }
        outputStream.write(buffer.array())
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

    fun readPayload(inputStream: InputStream, length: Int): ByteArray {
        val payload = ByteArray(length)
        var bytesRead = 0
        while (bytesRead < length) {
            val read = inputStream.read(payload, bytesRead, length - bytesRead)
            if (read == -1) {
                throw java.io.EOFException("End of stream reached while reading payload")
            }
            bytesRead += read
        }
        return payload
    }

    fun writeTransferRequest(outputStream: OutputStream, payload: TransferRequestPayload) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.TRANSFER_REQUEST, jsonBytes.size)
        outputStream.write(jsonBytes)
        outputStream.flush()
    }

    fun writeHeartbeat(outputStream: OutputStream, message: String) {
        val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.HEARTBEAT, messageBytes.size)
        outputStream.write(messageBytes)
        outputStream.flush()
    }

    fun writeTransferAccept(outputStream: OutputStream, payload: TransferAcceptPayload = TransferAcceptPayload()) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.TRANSFER_ACCEPT, jsonBytes.size)
        outputStream.write(jsonBytes)
        outputStream.flush()
    }

    fun writeTransferReject(outputStream: OutputStream, payload: TransferRejectPayload = TransferRejectPayload()) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.TRANSFER_REJECT, jsonBytes.size)
        outputStream.write(jsonBytes)
        outputStream.flush()
    }

    fun writeFileHeader(outputStream: OutputStream, payload: FileHeaderPayload) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.FILE_HEADER, jsonBytes.size)
        outputStream.write(jsonBytes)
        outputStream.flush()
    }

    fun writeTransferAck(outputStream: OutputStream, payload: TransferAckPayload = TransferAckPayload()) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.TRANSFER_ACK, jsonBytes.size)
        outputStream.write(jsonBytes)
        outputStream.flush()
    }

    fun writeFileComplete(outputStream: OutputStream, payload: FileCompletePayload) {
        val jsonBytes = payload.toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.FILE_COMPLETE, jsonBytes.size)
        outputStream.write(jsonBytes)
        outputStream.flush()
    }

    fun writeTransferComplete(outputStream: OutputStream, sessionId: String) {
        val sessionBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.TRANSFER_COMPLETE, sessionBytes.size)
        outputStream.write(sessionBytes)
        outputStream.flush()
    }

    fun writeTransferCancel(outputStream: OutputStream, sessionId: String) {
        val sessionBytes = sessionId.toByteArray(StandardCharsets.UTF_8)
        writeHeader(outputStream, FrameTag.TRANSFER_CANCEL, sessionBytes.size)
        outputStream.write(sessionBytes)
        outputStream.flush()
    }

    private fun maxPayloadSize(tag: FrameTag): Int = when (tag) {
        FrameTag.DATA_CHUNK -> Protocol.MAX_CHUNK_SIZE
        else -> Protocol.MAX_METADATA_SIZE
    }
}
