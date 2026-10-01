package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TAG = "DropFlowFileSender"

class FileSender {

    /**
     * Sends files from pre-opened InputStreams (required for Android Storage Access Framework URIs).
     * Each entry is (inputStream, fileSize, displayName). The caller is responsible for closing the streams.
     *
     * Returns null on success, or a human-readable error message describing the failure.
     */
    suspend fun sendTransferSessionFromStreams(
        socketInputStream: InputStream,
        socketOutputStream: OutputStream,
        requestPayload: TransferRequestPayload,
        fileStreams: List<Triple<InputStream, Long, String>>, // (stream, size, displayName)
        progressTracker: TransferProgressTracker
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting FileSender (streams) for session ${requestPayload.sessionId} (${fileStreams.size} files)")

        try {
            // 1. Send TransferRequest frame
            FrameUtils.writeTransferRequest(socketOutputStream, requestPayload)

            // 2. Read TransferAccept frame
            val acceptHeader = FrameUtils.readHeader(socketInputStream)
            if (acceptHeader.tag == FrameTag.TRANSFER_REJECT) {
                FrameUtils.readPayload(socketInputStream, acceptHeader.payloadLength)
                return@withContext "Transfer declined by recipient"
            }
            if (acceptHeader.tag != FrameTag.TRANSFER_ACCEPT) {
                Log.e(TAG, "Peer responded to transfer request with frame tag 0x${String.format("%02X", acceptHeader.tag.code)}")
                return@withContext "Unexpected response from recipient (0x${String.format("%02X", acceptHeader.tag.code)})"
            }
            FrameUtils.readPayload(socketInputStream, acceptHeader.payloadLength)

            val buffer = ByteArray(Protocol.DEFAULT_CHUNK_SIZE)

            for (idx in fileStreams.indices) {
                val (fileIn, fileSize, displayName) = fileStreams[idx]

                Log.d(TAG, "Sending file ${idx + 1}/${fileStreams.size}: '$displayName' ($fileSize bytes)")

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
                // Note: resumeOffset from receiver is ignored for SAF streams (no seek support)

                progressTracker.startFile(idx, displayName, fileSize, 0L)

                val messageDigest = MessageDigest.getInstance("SHA-256")
                var bytesSent = 0L

                BufferedInputStream(fileIn).use { bufferedIn ->
                    var read: Int
                    while (bufferedIn.read(buffer).also { read = it } != -1) {
                        FrameUtils.writeHeader(socketOutputStream, FrameTag.DATA_CHUNK, read)
                        socketOutputStream.write(buffer, 0, read)
                        socketOutputStream.flush()

                        messageDigest.update(buffer, 0, read)
                        bytesSent += read
                        progressTracker.onChunkTransferred(read.toLong())
                    }
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
