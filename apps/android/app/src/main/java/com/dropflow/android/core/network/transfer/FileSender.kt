package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TAG = "DropFlowFileSender"

class FileSender {

    /**
     * Opens one Android SAF stream at a time, after the recipient approves.
     *
     * Returns null on success, or a human-readable error message describing the failure.
     */
    suspend fun sendTransferSession(
        socketInputStream: InputStream,
        socketOutputStream: OutputStream,
        requestPayload: TransferRequestPayload,
        openFile: (Int) -> InputStream,
        progressTracker: TransferProgressTracker,
        setReadTimeout: (Int) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending ${requestPayload.totalFiles} files in session ${requestPayload.sessionId}")

        try {
            // 1. Send TransferRequest frame
            FrameUtils.writeTransferRequest(socketOutputStream, requestPayload)

            // 2. Read TransferAccept frame
            val acceptHeader = readAuthorizationResponse(socketInputStream, setReadTimeout)
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

            val buffer = ByteArray(Protocol.DEFAULT_CHUNK_SIZE)

            for ((idx, file) in requestPayload.files.withIndex()) {
                val fileSize = file.sizeBytes
                val displayName = file.relativePath

                Log.d(TAG, "Sending file ${idx + 1}/${requestPayload.totalFiles}: '$displayName' ($fileSize bytes)")

                // Send FileHeader — resume always starts at 0 for SAF streams (no seekable resume)
                FrameUtils.writeFileHeader(
                    socketOutputStream,
                    FileHeaderPayload(
                        fileIndex = idx,
                        relativePath = displayName,
                        sizeBytes = fileSize,
                        resumeOffset = 0L
                    )
                )

                // Read TransferAck
                val ackHeader = FrameUtils.readHeader(socketInputStream)
                if (ackHeader.tag == FrameTag.TRANSFER_CANCEL) {
                    FrameUtils.readPayload(socketInputStream, ackHeader.payloadLength)
                    throw TransferCancelledByPeerException()
                }
                if (ackHeader.tag != FrameTag.TRANSFER_ACK) {
                    throw IllegalStateException("Expected TRANSFER_ACK, received ${ackHeader.tag}")
                }
                val ack = TransferAckPayload.fromJsonString(
                    String(FrameUtils.readPayload(socketInputStream, ackHeader.payloadLength), StandardCharsets.UTF_8)
                )
                if (!ack.status.equals("ACCEPTED", ignoreCase = true)) {
                    throw IllegalStateException("Receiver rejected file '$displayName': ${ack.status}")
                }
                check(ack.resumeOffset == 0L) { "The recipient requested resume, which Android doesn't support yet" }

                progressTracker.startFile(idx, displayName, fileSize, 0L)

                val messageDigest = MessageDigest.getInstance("SHA-256")
                var bytesSent = 0L

                BufferedInputStream(openFile(idx)).use { bufferedIn ->
                    while (bytesSent < fileSize) {
                        val read = bufferedIn.read(buffer, 0, minOf(buffer.size.toLong(), fileSize - bytesSent).toInt())
                        if (read <= 0) break
                        FrameUtils.writeHeader(socketOutputStream, FrameTag.DATA_CHUNK, read)
                        socketOutputStream.write(buffer, 0, read)
                        socketOutputStream.flush()

                        messageDigest.update(buffer, 0, read)
                        bytesSent += read
                        progressTracker.onChunkTransferred(read.toLong())
                    }
                    check(bufferedIn.read() == -1) { "'$displayName' grew after it was selected" }
                }

                if (bytesSent != fileSize) {
                    throw IllegalStateException("Read $bytesSent bytes for '$displayName', expected $fileSize")
                }

                progressTracker.finishFile()

                val checksumHex = messageDigest.digest().joinToString("") { "%02x".format(it) }
                Log.d(TAG, "Sent file '$displayName' complete. SHA-256: $checksumHex")

                FrameUtils.writeFileComplete(
                    socketOutputStream,
                    FileCompletePayload(
                        fileIndex = idx,
                        bytesWritten = bytesSent,
                        sha256Checksum = checksumHex
                    )
                )
            }

            progressTracker.finishSession()
            // Send TransferComplete
            FrameUtils.writeTransferComplete(socketOutputStream, requestPayload.sessionId)

            // Read final TransferAck
            val finalAckHeader = FrameUtils.readHeader(socketInputStream)
            if (finalAckHeader.tag != FrameTag.TRANSFER_ACK) {
                throw IllegalStateException("Expected final TRANSFER_ACK, received ${finalAckHeader.tag}")
            }
            val finalAckBytes = FrameUtils.readPayload(socketInputStream, finalAckHeader.payloadLength)
            val finalAck = String(finalAckBytes, StandardCharsets.UTF_8)
            val finalStatus = runCatching { TransferAckPayload.fromJsonString(finalAck).status }.getOrDefault(finalAck)
            if (!finalStatus.equals("ACK", ignoreCase = true) && !finalStatus.equals("ACCEPTED", ignoreCase = true)) {
                throw IllegalStateException("Receiver did not confirm transfer completion: $finalStatus")
            }

            Log.d(TAG, "FileSender (streams) completed session ${requestPayload.sessionId} cleanly")
            null
        } catch (e: TransferCancelledByPeerException) {
            Log.d(TAG, "Session ${requestPayload.sessionId} cancelled by recipient")
            "Transfer cancelled by recipient"
        } catch (e: java.io.IOException) {
            Log.e(TAG, "FileSender (streams) connection lost for ${requestPayload.sessionId}: ${e.message}", e)
            if (e.message?.contains("ETIMEDOUT", ignoreCase = true) == true ||
                e.message?.contains("timeout", ignoreCase = true) == true
            ) {
                "Connection to recipient timed out"
            } else {
                "Connection to recipient was lost"
            }
        } catch (e: Exception) {
            Log.e(TAG, "FileSender (streams) session failed for ${requestPayload.sessionId}: ${e.message}", e)
            e.message ?: "Outgoing transfer failed"
        }
    }

    private class TransferCancelledByPeerException : Exception()
}
