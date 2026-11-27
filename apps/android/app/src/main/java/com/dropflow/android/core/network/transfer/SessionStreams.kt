package com.dropflow.android.core.network.transfer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/** The person on this device cancelled. Not an IOException: the connection is fine. */
internal class TransferCancelledException : RuntimeException("Transfer cancelled")

/** The other device cancelled, declined or reported an error. It needs no notice back. */
internal class PeerStoppedException(message: String) : RuntimeException(message)

/** Something failed on this device (reading a source file, saving a received one). */
internal class LocalFailureException(message: String, val notice: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Socket input that notices a local cancel while it is blocked. The socket
 * runs with a short SO_TIMEOUT ([SLICE_MS]); [timeoutMs] is the real limit
 * for one read. A timed-out socket read consumes nothing, so retrying is safe.
 */
internal class SessionInput(
    private val raw: InputStream,
    private val isCancelled: () -> Boolean
) : InputStream() {
    @Volatile
    var timeoutMs: Int = Protocol.READ_TIMEOUT_MS

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val started = System.nanoTime()
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            try {
                return raw.read(buffer, offset, length)
            } catch (timeout: SocketTimeoutException) {
                if ((System.nanoTime() - started) / 1_000_000 >= timeoutMs) throw timeout
            }
        }
    }

    override fun available(): Int = raw.available()

    override fun close() = raw.close()

    companion object {
        const val SLICE_MS = 200
    }
}

/**
 * Socket output for the one thread that writes a session. Every caller writes
 * whole frames, so after any completed write the stream is at a frame
 * boundary. [broken] means a write failed part-way and nothing may follow.
 */
internal class FrameOutput(private val raw: OutputStream) : OutputStream() {
    @Volatile
    var broken = false
        private set

    @Volatile
    var lastWriteNanos = System.nanoTime()
        private set

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        try {
            raw.write(buffer, offset, length)
        } catch (error: IOException) {
            broken = true
            throw error
        }
        lastWriteNanos = System.nanoTime()
    }

    override fun flush() {
        try {
            raw.flush()
        } catch (error: IOException) {
            broken = true
            throw error
        }
    }

    override fun close() = raw.close()

    /** Best-effort final frame (cancel or error). Skipped when a frame is half written. */
    fun notice(tag: FrameTag, payload: ByteArray): Boolean {
        if (broken) return false
        return try {
            FrameUtils.writeFrame(this, tag, payload)
            true
        } catch (_: IOException) {
            false
        }
    }

    companion object {
        fun of(output: OutputStream): FrameOutput = output as? FrameOutput ?: FrameOutput(output)
    }
}

/** Reads the next frame that is not a keep-alive. */
internal fun readReply(input: InputStream): Pair<FrameTag, ByteArray> {
    while (true) {
        val header = FrameUtils.readHeader(input)
        val payload = FrameUtils.readPayload(input, header.payloadLength)
        if (header.tag != FrameTag.HEARTBEAT) return header.tag to payload
    }
}

/** The text of an ERROR frame, bounded before it reaches the screen. */
internal fun noticeText(payload: ByteArray): String =
    String(payload, Charsets.UTF_8).filterNot { it.isISOControl() }.take(200)

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        out[index * 2] = digits[value ushr 4]
        out[index * 2 + 1] = digits[value and 0x0F]
    }
    return String(out)
}

/** True for "no space left on device", however deep it is wrapped. */
internal fun isOutOfSpace(error: Throwable): Boolean {
    var current: Throwable? = error
    repeat(8) {
        val cause = current ?: return false
        val message = cause.message.orEmpty()
        if (message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)) return true
        current = cause.cause
    }
    return false
}
