package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TAG = "DropFlowFileReceiver"

private const val MISMATCH_NOTICE = "the data did not match the transfer"

class FileReceiver(private val storage: ReceiveStorage) {
    var failureReason: String? = null
        private set

    /**
     * Runs an approved transfer: sends the acceptance, receives and verifies
     * every file, saves them, then confirms. Grants `batch` when the sender
     * offers it; otherwise this is the original DFP/1 exchange. With [direct]
     * it also grants a private link when the two devices can form one, and
     * moves the session onto it; the link is released when the session ends.
     */
    suspend fun receiveTransferSession(
        inputStream: InputStream,
        outputStream: OutputStream,
        requestPayload: TransferRequestPayload,
        progressTracker: TransferProgressTracker,
        isCancelled: () -> Boolean = { false },
        sendNotice: ((FrameTag, ByteArray) -> Unit)? = null,
        direct: DirectOptions? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting FileReceiver for session ${requestPayload.sessionId} (${requestPayload.totalFiles} files, ${requestPayload.totalSizeBytes} bytes)")
        // Replaced by the direct link's streams when the session moves there.
        var input = inputStream
        var output = FrameOutput.of(outputStream)
        var linked: Socket? = null
        var setup: LinkSetup? = null
        // Resume needs staged data that survives the session; Android does not keep any yet.
        val batch = Protocol.FEATURE_BATCH in requestPayload.features
        val frames = BulkFrameReader()
        val started = System.nanoTime()
        var pipeline: ReceivePipeline? = null

        // Runs between frames and while waiting for storage. A batch sender may
        // have written everything and be waiting for the final reply while this
        // device is still saving, so slow storage must not look like a dead connection.
        val tick = {
            if (isCancelled()) throw TransferCancelledException()
            if (batch && System.nanoTime() - output.lastWriteNanos >= Protocol.HEARTBEAT_INTERVAL_MS * 1_000_000) {
                FrameUtils.writeFrame(output, FrameTag.HEARTBEAT, ByteArray(0))
            }
        }

        try {
            ReceiveValidation.request(requestPayload)
            // A direct link needs batch, both devices to offer it, and a workable pairing.
            val theirs = requestPayload.direct
            val mine = direct?.link?.let { runCatching(it::caps).getOrNull() }
            val host = if (batch && mine != null && theirs != null && Protocol.FEATURE_DIRECT in requestPayload.features) {
                chooseHost(sender = theirs, receiver = mine)
            } else {
                null
            }
            FrameUtils.writeTransferAccept(
                output,
                TransferAcceptPayload(
                    status = "ACCEPTED",
                    features = listOfNotNull(
                        Protocol.FEATURE_BATCH.takeIf { batch },
                        Protocol.FEATURE_DIRECT.takeIf { host != null }
                    ),
                    directHost = host
                )
            )
            if (host != null && direct != null) {
                progressTracker.startLinking()
                val socket = LinkSetup(input, output, direct.link, isCancelled, direct.limits, ::senderStopped)
                    .also { setup = it }.run(iHost = host == DirectCaps.HOST_RECEIVER)
                if (socket != null) {
                    // The rest of the session, notices included, uses the new connection.
                    linked = socket
                    val (linkInput, linkOutput) = direct.adopt(socket)
                    input = linkInput
                    output = FrameOutput.of(linkOutput)
                }
                progressTracker.finishLinking(direct = socket != null)
                Log.d(TAG, "Session ${requestPayload.sessionId} ${if (socket != null) "moved to the direct link" else "stays on the shared network"}")
            }
            val files = ReceivePipeline(storage, requestPayload.files.map { it.relativePath }).also { pipeline = it }
            val messageDigest = MessageDigest.getInstance("SHA-256")

            for (i in 0 until requestPayload.totalFiles) {
                tick()
                val header = nextFrame(input, batch)
                if (header.tag != FrameTag.FILE_HEADER) {
                    throw IllegalStateException("Expected FILE_HEADER frame tag, received 0x${String.format("%02X", header.tag.code)}")
                }

                val headerPayloadBytes = FrameUtils.readPayload(input, header.payloadLength)
                val fileHeader = FileHeaderPayload.fromJsonString(String(headerPayloadBytes, StandardCharsets.UTF_8))
                ReceiveValidation.header(requestPayload, i, fileHeader)

                progressTracker.startFile(i, fileHeader.relativePath, fileHeader.sizeBytes)

                // The storage target (MediaStore on API 29+, legacy .part file below) was prepared ahead.
                val target = files.take(i, tick)
                // DFP/1 acknowledges every file header; batch mode sends nothing until the end.
                if (!batch) FrameUtils.writeTransferAck(output, TransferAckPayload(status = "ACCEPTED", resumeOffset = 0L))

                var bytesWritten = 0L
                while (bytesWritten < fileHeader.sizeBytes) {
                    val chunkHeader = nextFrame(input, batch)
                    if (chunkHeader.tag != FrameTag.DATA_CHUNK) {
                        throw IllegalStateException("Expected DATA_CHUNK frame tag, received 0x${String.format("%02X", chunkHeader.tag.code)}")
                    }
                    val remainingBytes = fileHeader.sizeBytes - bytesWritten
                    val length = chunkHeader.payloadLength
                    val chunkBytes = frames.read(input, length, remainingBytes)
                    try {
                        target.stream.write(chunkBytes, 0, length)
                    } catch (e: IOException) {
                        throw storageFailure(e)
                    }
                    messageDigest.update(chunkBytes, 0, length)

                    bytesWritten += length
                    progressTracker.onChunkTransferred(length.toLong())
                    tick()
                }
                // Flushed, synced and closed in the background; joined before anything is published.
                files.finish(i, tick)

                progressTracker.finishFile()

                // Read FileComplete frame
                val compHeader = nextFrame(input, batch)
                if (compHeader.tag != FrameTag.FILE_COMPLETE) {
                    throw IllegalStateException("Expected FILE_COMPLETE frame tag, received 0x${String.format("%02X", compHeader.tag.code)}")
                }

                val compPayloadBytes = FrameUtils.readPayload(input, compHeader.payloadLength)
                val fileComp = FileCompletePayload.fromJsonString(String(compPayloadBytes, StandardCharsets.UTF_8))
                if (fileComp.fileIndex != fileHeader.fileIndex || fileComp.bytesWritten != bytesWritten) {
                    throw IllegalStateException("Invalid FILE_COMPLETE for '${fileHeader.relativePath}'")
                }

                // The sender's digest is required and must match what was written.
                val computedChecksum = messageDigest.digest().toHex()
                ReceiveValidation.checksum(fileHeader.sha256Checksum, fileComp.sha256Checksum, computedChecksum)
                Log.d(TAG, "File ${i + 1}/${requestPayload.totalFiles} '${target.displayName}' received and verified ($bytesWritten bytes)")
            }

            val finalHeader = nextFrame(input, batch)
            if (finalHeader.tag != FrameTag.TRANSFER_COMPLETE) {
                throw IllegalStateException("Expected TRANSFER_COMPLETE, received ${finalHeader.tag}")
            }
            val completedSessionId = String(FrameUtils.readPayload(input, finalHeader.payloadLength), StandardCharsets.UTF_8)
            if (completedSessionId.isNotBlank() && completedSessionId != requestPayload.sessionId) {
                throw IllegalStateException("TRANSFER_COMPLETE session ID mismatch")
            }
            // Session fully verified. Publish only once every file is on disk, so a
            // failed session never leaves partial output in the Downloads folder.
            progressTracker.finishSession()
            val streamed = System.nanoTime()
            files.awaitDurable(tick)
            val durable = System.nanoTime()
            // The last point where the person can still cancel.
            if (isCancelled()) throw TransferCancelledException()

            // From here saving is not interruptible: stopping would leave the
            // transfer half saved. A cancel or a lost connection no longer
            // matters; only a storage failure stops it, and then all is removed.
            var keepAlive = batch
            files.publishAll {
                if (keepAlive && System.nanoTime() - output.lastWriteNanos >= Protocol.HEARTBEAT_INTERVAL_MS * 1_000_000) {
                    keepAlive = sendQuietly(output, FrameTag.HEARTBEAT, ByteArray(0))
                }
            }
            files.close()
            pipeline = null
            Log.d(TAG, "Saved ${requestPayload.totalFiles} files: stream ${(streamed - started) / 1_000_000} ms, " +
                "sync ${(durable - streamed) / 1_000_000} ms, publish ${(System.nanoTime() - durable) / 1_000_000} ms")
            // A sender may report success only after durable output has been published.
            // If the confirmation cannot be sent the sender reports a failure,
            // but nothing is missing on this device.
            val ack = TransferAckPayload(status = "ACK").toJsonObject().toString().toByteArray(StandardCharsets.UTF_8)
            if (!sendQuietly(output, FrameTag.TRANSFER_ACK, ack)) {
                Log.w(TAG, "Files saved, but the confirmation was not sent for ${requestPayload.sessionId}")
            }
            // The private network goes away right after this returns; let the confirmation arrive first.
            linked?.let(::awaitPeerClose)
            Log.i(TAG, TransferPerf.summary("receive", requestPayload.totalFiles, requestPayload.totalSizeBytes, System.nanoTime() - started))

            true
        } catch (e: Exception) {
            Log.e(TAG, "FileReceiver session failed for ${requestPayload.sessionId}: ${e.message}", e)
            // The reason goes out first, at a frame boundary: only this thread
            // writes, and it writes whole frames. Batch mode only; a DFP/1
            // sender does not expect these frames.
            val notice: Pair<FrameTag, String>? = when {
                e is TransferCancelledException || isCancelled() -> FrameTag.TRANSFER_CANCEL to requestPayload.sessionId
                e is PeerStoppedException -> null
                e is LocalFailureException -> FrameTag.ERROR to e.notice
                e is IOException -> null // the connection failed
                else -> FrameTag.ERROR to MISMATCH_NOTICE
            }
            failureReason = e.message ?: "File transfer failed"
            // A device left the shared network for a link that never came up:
            // that connection is finished, and nothing more is said on it.
            val stranded = linked == null && setup?.left == true
            if (batch && notice != null && !stranded) {
                val payload = notice.second.toByteArray(StandardCharsets.UTF_8)
                if (sendNotice != null) runCatching { sendNotice(notice.first, payload) } else output.notice(notice.first, payload)
            }
            // Everything this session allocated is removed, published or not.
            pipeline?.discard()
            false
        } finally {
            // However the session ended: the private network is given back.
            linked?.let { runCatching { it.close() } }
            direct?.link?.let { runCatching { it.close() } }
        }
    }

    private fun senderStopped(tag: FrameTag, payload: ByteArray) = PeerStoppedException(
        if (tag == FrameTag.ERROR) "The sending device stopped the transfer: ${noticeText(payload)}"
        else "Transfer cancelled by sender"
    )

    /** Writes a frame whose failure must not stop the caller. */
    private fun sendQuietly(output: FrameOutput, tag: FrameTag, payload: ByteArray): Boolean = try {
        FrameUtils.writeFrame(output, tag, payload)
        true
    } catch (e: IOException) {
        Log.w(TAG, "Could not send $tag: ${e.message}")
        false
    }

    /**
     * The next frame of the stream. Handles the frames that may arrive at any
     * point: a cancel, and in batch mode keep-alives and the sender's errors.
     */
    private fun nextFrame(input: InputStream, batch: Boolean): FrameHeader {
        while (true) {
            val header = FrameUtils.readHeader(input)
            when {
                header.tag == FrameTag.TRANSFER_CANCEL -> throw PeerStoppedException("Transfer cancelled by sender")
                // Keep-alives, and a late "the direct link failed" after both sides settled.
                batch && header.tag.isIdle -> FrameUtils.readPayload(input, header.payloadLength)
                batch && header.tag == FrameTag.ERROR -> throw PeerStoppedException(
                    "The sending device stopped the transfer: ${noticeText(FrameUtils.readPayload(input, header.payloadLength))}"
                )
                else -> return header
            }
        }
    }
}
