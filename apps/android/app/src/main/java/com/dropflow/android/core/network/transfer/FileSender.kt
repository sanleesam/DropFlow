package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TAG = "DropFlowFileSender"

// Batch mode groups control frames and small data frames into writes of this size.
private const val FLUSH_AT = 64 * 1024
private const val SMALL_FRAME = 32 * 1024

private const val NEEDS_NEWER_PEER =
    "The other device needs a newer version of DropFlow to receive this many files at once."
private const val READ_NOTICE = "it could not read a file"

class FileSender {

    /**
     * Opens one Android SAF stream at a time, after the recipient approves.
     * Uses the DFP/1.1 features the request offers and the recipient grants;
     * without them this is the original DFP/1 exchange.
     *
     * Returns null on success, or a human-readable error message describing the failure.
     */
    suspend fun sendTransferSession(
        socketInputStream: InputStream,
        socketOutputStream: OutputStream,
        requestPayload: TransferRequestPayload,
        openFile: (Int) -> InputStream,
        progressTracker: TransferProgressTracker,
        setReadTimeout: (Int) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        sendNotice: ((FrameTag, ByteArray) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending ${requestPayload.totalFiles} files in session ${requestPayload.sessionId}")

        val output = FrameOutput.of(socketOutputStream)
        // Only this thread writes, and only whole frames, so a notice sent from
        // here is always at a frame boundary.
        val notify = { tag: FrameTag, text: String ->
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            if (sendNotice != null) runCatching { sendNotice(tag, payload) } else output.notice(tag, payload)
        }
        var batch = false

        try {
            // 1. Send TransferRequest frame
            val requestJson = FrameUtils.encodeTransferRequest(requestPayload)
            val large = requestJson.size > Protocol.MAX_METADATA_SIZE
            val asked = System.nanoTime()

            // 2. Read TransferAccept frame
            val acceptHeader = try {
                FrameUtils.writeTransferRequest(output, requestJson)
                readAuthorizationResponse(socketInputStream, setReadTimeout)
            } catch (e: IOException) {
                // A peer that predates large manifests drops the connection at once.
                val quick = System.nanoTime() - asked < Protocol.LARGE_REQUEST_REJECT_WINDOW_MS * 1_000_000
                if (large && quick && e !is SocketTimeoutException) throw PeerStoppedException(NEEDS_NEWER_PEER)
                throw e
            }
            if (acceptHeader.tag == FrameTag.TRANSFER_REJECT) {
                val payload = acceptHeader.payload.toString(Charsets.UTF_8)
                val reason = runCatching { TransferRejectPayload.fromJsonString(payload).reason }.getOrDefault(payload)
                return@withContext when {
                    reason.contains("busy", ignoreCase = true) -> "The recipient is busy. Try again shortly."
                    reason.contains("timed out", ignoreCase = true) -> "The recipient did not respond in time."
                    else -> "Transfer declined by recipient"
                }
            }
            if (acceptHeader.tag != FrameTag.TRANSFER_ACCEPT) {
                Log.e(TAG, "Peer responded to transfer request with frame tag 0x${String.format("%02X", acceptHeader.tag.code)}")
                return@withContext "Unexpected response from recipient (0x${String.format("%02X", acceptHeader.tag.code)})"
            }

            // A feature is on only if we offered it and the recipient granted it.
            val granted = TransferAcceptPayload.grantedFeatures(acceptHeader.payload)
            val agreed = { name: String -> name in requestPayload.features && name in granted }
            batch = agreed(Protocol.FEATURE_BATCH)
            val resume = batch && agreed(Protocol.FEATURE_RESUME)
            val held = if (resume) readResumeState(socketInputStream) else mutableMapOf()
            Log.d(TAG, "Session ${requestPayload.sessionId} negotiated batch=$batch resume=$resume")

            val frames = BulkFrameWriter()
            val outbox = Outbox(output, batch)
            val started = System.nanoTime()
            var lastPeerCheck = started
            // Runs between frames: stop on a local cancel, and in batch mode
            // look for a cancel or error from the recipient without blocking.
            val tick = {
                if (isCancelled()) throw TransferCancelledException()
                val now = System.nanoTime()
                if (batch && now - lastPeerCheck >= Protocol.PEER_CHECK_INTERVAL_MS * 1_000_000) {
                    lastPeerCheck = now
                    checkPeer(socketInputStream)
                }
            }

            for ((idx, file) in requestPayload.files.withIndex()) {
                tick()
                val fileSize = file.sizeBytes
                val displayName = file.relativePath

                Log.d(TAG, "Sending file ${idx + 1}/${requestPayload.totalFiles}: '$displayName' ($fileSize bytes)")

                val messageDigest = MessageDigest.getInstance("SHA-256")
                var source: InputStream? = null
                var offset = 0L
                var bytesSent = 0L

                try {
                    // Continue only from bytes that are identical to ours.
                    val entry = held.remove(idx)
                    if (entry != null && entry.offset in 1..fileSize) {
                        outbox.flush()
                        val prefix = openSource(openFile, idx, displayName)
                        source = prefix
                        val matches = hashPrefix(prefix, entry.offset, frames.bytes, messageDigest) {
                            tick()
                            outbox.keepAlive()
                        } && runCatching { (messageDigest.clone() as MessageDigest).digest().toHex() }
                            .getOrNull().equals(entry.sha256, ignoreCase = true)
                        if (matches) {
                            offset = entry.offset
                        } else {
                            // Reopen and send the whole file.
                            source = null
                            runCatching { prefix.close() }
                            messageDigest.reset()
                        }
                    }

                    // SAF streams cannot seek, so without a verified prefix a file starts at 0.
                    outbox.control(
                        FrameTag.FILE_HEADER,
                        FileHeaderPayload(
                            fileIndex = idx,
                            relativePath = displayName,
                            sizeBytes = fileSize,
                            resumeOffset = offset
                        ).toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
                    )

                    // DFP/1 waits for the recipient before each file; batch mode does not.
                    if (!batch) awaitFileAck(socketInputStream, displayName)

                    progressTracker.startFile(idx, displayName, fileSize, offset)

                    val input = source ?: openSource(openFile, idx, displayName).also { source = it }
                    bytesSent = offset
                    while (bytesSent < fileSize) {
                        tick()
                        val want = minOf(frames.payloadCapacity.toLong(), fileSize - bytesSent).toInt()
                        val read = readSource(displayName) { input.read(frames.bytes, Protocol.HEADER_SIZE, want) }
                        if (read <= 0) break
                        messageDigest.update(frames.bytes, Protocol.HEADER_SIZE, read)
                        outbox.data(frames, read)

                        bytesSent += read
                        progressTracker.onChunkTransferred(read.toLong())
                    }
                    if (bytesSent != fileSize) {
                        throw LocalFailureException("Read $bytesSent bytes for '$displayName', expected $fileSize", READ_NOTICE)
                    }
                    if (readSource(displayName) { input.read() } != -1) {
                        throw LocalFailureException("'$displayName' grew after it was selected", READ_NOTICE)
                    }
                } finally {
                    runCatching { source?.close() }
                }

                progressTracker.finishFile()

                val checksumHex = messageDigest.digest().toHex()
                Log.d(TAG, "Sent file '$displayName' complete. SHA-256: $checksumHex")

                outbox.control(
                    FrameTag.FILE_COMPLETE,
                    FileCompletePayload(
                        fileIndex = idx,
                        bytesWritten = bytesSent,
                        sha256Checksum = checksumHex
                    ).toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
                )
            }

            progressTracker.finishSession()
            // Send TransferComplete, and everything still grouped before it.
            outbox.control(FrameTag.TRANSFER_COMPLETE, requestPayload.sessionId.toByteArray(StandardCharsets.UTF_8))
            outbox.flush()

            // Complete only when the recipient confirms that every file is saved.
            awaitFinalAck(socketInputStream, batch)

            Log.i(TAG, TransferPerf.summary("send", requestPayload.totalFiles, requestPayload.totalSizeBytes, System.nanoTime() - started))
            Log.d(TAG, "FileSender (streams) completed session ${requestPayload.sessionId} cleanly")
            null
        } catch (e: Exception) {
            when {
                e is TransferCancelledException || isCancelled() -> {
                    Log.d(TAG, "Session ${requestPayload.sessionId} cancelled on this device")
                    notify(FrameTag.TRANSFER_CANCEL, requestPayload.sessionId)
                    "Transfer cancelled"
                }
                e is PeerStoppedException -> {
                    Log.d(TAG, "Session ${requestPayload.sessionId} stopped by recipient: ${e.message}")
                    e.message
                }
                e is LocalFailureException -> {
                    Log.e(TAG, "FileSender (streams) could not read a file for ${requestPayload.sessionId}: ${e.message}", e)
                    if (batch) notify(FrameTag.ERROR, e.notice)
                    e.message
                }
                e is IOException -> {
                    Log.e(TAG, "FileSender (streams) connection lost for ${requestPayload.sessionId}: ${e.message}", e)
                    val said = if (batch) lateNotice(socketInputStream, setReadTimeout) else null
                    said ?: if (e is SocketTimeoutException ||
                        e.message?.contains("ETIMEDOUT", ignoreCase = true) == true ||
                        e.message?.contains("timeout", ignoreCase = true) == true
                    ) {
                        "Connection to recipient timed out"
                    } else {
                        "Connection to recipient was lost"
                    }
                }
                else -> {
                    Log.e(TAG, "FileSender (streams) session failed for ${requestPayload.sessionId}: ${e.message}", e)
                    if (batch) notify(FrameTag.ERROR, "it could not continue")
                    e.message ?: "Outgoing transfer failed"
                }
            }
        }
    }

    /** What the recipient already holds from an interrupted attempt, by file index. */
    private fun readResumeState(input: InputStream): MutableMap<Int, ResumeEntry> {
        val (tag, payload) = readReply(input)
        if (tag == FrameTag.TRANSFER_CANCEL || tag == FrameTag.ERROR) throw stopped(tag, payload)
        if (tag != FrameTag.RESUME_STATE) throw PeerStoppedException("Expected RESUME_STATE, received $tag")
        val entries = runCatching { ResumeEntry.listFromJsonString(String(payload, StandardCharsets.UTF_8)) }
            .getOrElse { throw PeerStoppedException("Invalid resume state from the other device") }
        return entries.associateByTo(HashMap()) { it.fileIndex }
    }

    /** DFP/1: the recipient acknowledges every file header before its data. */
    private fun awaitFileAck(input: InputStream, displayName: String) {
        val ackHeader = FrameUtils.readHeader(input)
        if (ackHeader.tag == FrameTag.TRANSFER_CANCEL) {
            FrameUtils.readPayload(input, ackHeader.payloadLength)
            throw PeerStoppedException("Transfer cancelled by recipient")
        }
        if (ackHeader.tag != FrameTag.TRANSFER_ACK) {
            throw IllegalStateException("Expected TRANSFER_ACK, received ${ackHeader.tag}")
        }
        val ack = TransferAckPayload.fromJsonString(
            String(FrameUtils.readPayload(input, ackHeader.payloadLength), StandardCharsets.UTF_8)
        )
        if (!ack.status.equals("ACCEPTED", ignoreCase = true)) {
            throw IllegalStateException("Receiver rejected file '$displayName': ${ack.status}")
        }
        check(ack.resumeOffset == 0L) { "The recipient requested resume, which Android doesn't support yet" }
    }

    private fun awaitFinalAck(input: InputStream, batch: Boolean) {
        val tag: FrameTag
        val finalAckBytes: ByteArray
        if (batch) {
            // The recipient sends keep-alives while it saves, and may still stop with a reason.
            val reply = readReply(input)
            tag = reply.first
            finalAckBytes = reply.second
            if (tag == FrameTag.TRANSFER_CANCEL || tag == FrameTag.ERROR) throw stopped(tag, finalAckBytes)
        } else {
            val finalAckHeader = FrameUtils.readHeader(input)
            tag = finalAckHeader.tag
            finalAckBytes = if (tag == FrameTag.TRANSFER_ACK) {
                FrameUtils.readPayload(input, finalAckHeader.payloadLength)
            } else {
                ByteArray(0)
            }
        }
        if (tag != FrameTag.TRANSFER_ACK) {
            throw IllegalStateException("Expected final TRANSFER_ACK, received $tag")
        }
        val finalAck = String(finalAckBytes, StandardCharsets.UTF_8)
        val finalStatus = runCatching { TransferAckPayload.fromJsonString(finalAck).status }.getOrDefault(finalAck)
        val confirmed = finalStatus.equals("ACK", ignoreCase = true) ||
            (!batch && finalStatus.equals("ACCEPTED", ignoreCase = true))
        if (!confirmed) {
            throw IllegalStateException("Receiver did not confirm transfer completion: $finalStatus")
        }
    }

    /** Batch mode: has the recipient cancelled or failed while we were sending? Never blocks on an idle peer. */
    private fun checkPeer(input: InputStream) {
        while (input.available() > 0) {
            val header = FrameUtils.readHeader(input)
            val payload = FrameUtils.readPayload(input, header.payloadLength)
            if (header.tag != FrameTag.HEARTBEAT) throw stopped(header.tag, payload)
        }
    }

    /** After a lost connection: the recipient may have said why just before it closed. */
    private fun lateNotice(input: InputStream, setReadTimeout: (Int) -> Unit): String? = runCatching {
        setReadTimeout(1_000)
        val (tag, payload) = readReply(input)
        if (tag == FrameTag.TRANSFER_CANCEL || tag == FrameTag.ERROR) stopped(tag, payload).message else null
    }.getOrNull()

    private fun stopped(tag: FrameTag, payload: ByteArray) = PeerStoppedException(
        when (tag) {
            FrameTag.TRANSFER_CANCEL -> "Transfer cancelled by recipient"
            FrameTag.ERROR -> "The other device stopped the transfer: ${noticeText(payload)}"
            else -> "Unexpected $tag frame from the other device"
        }
    )

    private fun openSource(openFile: (Int) -> InputStream, index: Int, displayName: String): InputStream =
        readSource(displayName) { BufferedInputStream(openFile(index)) }

    /** A failing source file is a problem on this device, not a lost connection. */
    private inline fun <T> readSource(displayName: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw LocalFailureException(e.message ?: "Could not read '$displayName'", READ_NOTICE, e)
    }

    /** Hashes the first [length] bytes of [source]. False when they could not all be read. */
    private fun hashPrefix(
        source: InputStream,
        length: Long,
        scratch: ByteArray,
        digest: MessageDigest,
        tick: () -> Unit
    ): Boolean {
        var left = length
        while (left > 0) {
            tick()
            val read = try {
                source.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            } catch (_: IOException) {
                return false
            }
            if (read <= 0) return false
            digest.update(scratch, 0, read)
            left -= read
        }
        return true
    }

    /**
     * In batch mode control frames and small data frames share a socket write.
     * Otherwise every frame is its own write, exactly as in DFP/1.
     */
    private class Outbox(private val output: FrameOutput, private val batch: Boolean) {
        private var buffer = ByteArray(if (batch) 2 * FLUSH_AT else 0)
        private var length = 0

        fun control(tag: FrameTag, payload: ByteArray) {
            if (!batch) {
                FrameUtils.writeFrame(output, tag, payload)
                return
            }
            reserve(Protocol.HEADER_SIZE + payload.size)
            FrameUtils.encodeHeader(buffer, tag, payload.size, length)
            payload.copyInto(buffer, length + Protocol.HEADER_SIZE)
            length += Protocol.HEADER_SIZE + payload.size
            if (length >= FLUSH_AT) flush()
        }

        /** Sends the data frame whose payload is already in [frames]. */
        fun data(frames: BulkFrameWriter, payloadLength: Int) {
            if (batch && payloadLength < SMALL_FRAME) {
                reserve(Protocol.HEADER_SIZE + payloadLength)
                FrameUtils.encodeHeader(buffer, FrameTag.DATA_CHUNK, payloadLength, length)
                frames.bytes.copyInto(buffer, length + Protocol.HEADER_SIZE, Protocol.HEADER_SIZE, Protocol.HEADER_SIZE + payloadLength)
                length += Protocol.HEADER_SIZE + payloadLength
                if (length >= FLUSH_AT) flush()
                return
            }
            flush()
            frames.send(output, payloadLength)
        }

        fun flush() {
            if (length == 0) return
            val pending = length
            length = 0
            output.write(buffer, 0, pending)
            output.flush()
        }

        /** Keeps the recipient's read timeout from firing during long local work. */
        fun keepAlive() {
            if (batch && System.nanoTime() - output.lastWriteNanos >= Protocol.HEARTBEAT_INTERVAL_MS * 1_000_000) {
                control(FrameTag.HEARTBEAT, ByteArray(0))
                flush()
            }
        }

        private fun reserve(extra: Int) {
            if (length + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + extra))
        }
    }
}
