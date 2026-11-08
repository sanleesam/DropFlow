package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private const val TAG = "DropFlowFileReceiver"

class FileReceiver(
    context: android.content.Context,
    private val storageManager: StorageManager = StorageManager(context)
) {
    var failureReason: String? = null
        private set

    suspend fun receiveTransferSession(
        inputStream: InputStream,
        outputStream: OutputStream,
        requestPayload: TransferRequestPayload,
        progressTracker: TransferProgressTracker
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting FileReceiver for session ${requestPayload.sessionId} (${requestPayload.totalFiles} files, ${requestPayload.totalSizeBytes} bytes)")
        val activeTargets = mutableListOf<WritableTarget>()
        val completedTargets = mutableListOf<WritableTarget>()
        val frames = BulkFrameReader()

        try {
            ReceiveValidation.request(requestPayload)
            for (i in 0 until requestPayload.totalFiles) {
                val header = readReceiveHeader(inputStream)
                if (header.tag != FrameTag.FILE_HEADER) {
                    throw IllegalStateException("Expected FILE_HEADER frame tag, received 0x${String.format("%02X", header.tag.code)}")
                }

                val headerPayloadBytes = FrameUtils.readPayload(inputStream, header.payloadLength)
                val fileHeader = FileHeaderPayload.fromJsonString(String(headerPayloadBytes, StandardCharsets.UTF_8))
                ReceiveValidation.header(requestPayload, i, fileHeader)
                Log.d(TAG, "Receiving file ${i + 1}/${requestPayload.totalFiles}: '${fileHeader.relativePath}' (${fileHeader.sizeBytes} bytes)")

                progressTracker.startFile(i, fileHeader.relativePath, fileHeader.sizeBytes)

                // Open the storage target (MediaStore on API 29+, legacy .part file below).
                val target = storageManager.prepareTargetStream(fileHeader.relativePath)
                activeTargets += target
                FrameUtils.writeTransferAck(outputStream, TransferAckPayload(status = "ACCEPTED", resumeOffset = 0L))

                val messageDigest = MessageDigest.getInstance("SHA-256")
                var bytesWritten = 0L

                target.stream.use { fileOut ->
                    while (bytesWritten < fileHeader.sizeBytes) {
                        val chunkHeader = readReceiveHeader(inputStream)
                        if (chunkHeader.tag != FrameTag.DATA_CHUNK) {
                            throw IllegalStateException("Expected DATA_CHUNK frame tag, received 0x${String.format("%02X", chunkHeader.tag.code)}")
                        }
                        val remainingBytes = fileHeader.sizeBytes - bytesWritten
                        val length = chunkHeader.payloadLength
                        val chunkBytes = frames.read(inputStream, length, remainingBytes)
                        fileOut.write(chunkBytes, 0, length)
                        messageDigest.update(chunkBytes, 0, length)

                        bytesWritten += length
                        progressTracker.onChunkTransferred(length.toLong())
                    }
                    fileOut.flush()
                    fileOut.fd.sync()
                }

                progressTracker.finishFile()

                // Read FileComplete frame
                val compHeader = readReceiveHeader(inputStream)
                if (compHeader.tag != FrameTag.FILE_COMPLETE) {
                    throw IllegalStateException("Expected FILE_COMPLETE frame tag, received 0x${String.format("%02X", compHeader.tag.code)}")
                }

                val compPayloadBytes = FrameUtils.readPayload(inputStream, compHeader.payloadLength)
                val fileComp = FileCompletePayload.fromJsonString(String(compPayloadBytes, StandardCharsets.UTF_8))
                if (fileComp.fileIndex != fileHeader.fileIndex || fileComp.bytesWritten != bytesWritten) {
                    throw IllegalStateException("Invalid FILE_COMPLETE for '${fileHeader.relativePath}'")
                }

                val computedChecksum = messageDigest.digest().joinToString("") { "%02x".format(it) }
                Log.d(TAG, "File '${fileHeader.relativePath}' completed. Computed SHA-256: $computedChecksum, Sender SHA-256: ${fileComp.sha256Checksum}")

                ReceiveValidation.checksum(fileHeader.sha256Checksum, fileComp.sha256Checksum, computedChecksum)

                // File verified — remember it for finalization once the whole
                // session completes, matching the desktop receiver semantics.
                completedTargets += target
                activeTargets.remove(target)
                Log.d(TAG, "File '${target.displayName}' received and verified (${bytesWritten} bytes)")
            }

            val finalHeader = readReceiveHeader(inputStream)
            if (finalHeader.tag != FrameTag.TRANSFER_COMPLETE) {
                throw IllegalStateException("Expected TRANSFER_COMPLETE, received ${finalHeader.tag}")
            }
            val completedSessionId = String(FrameUtils.readPayload(inputStream, finalHeader.payloadLength), StandardCharsets.UTF_8)
            if (completedSessionId.isNotBlank() && completedSessionId != requestPayload.sessionId) {
                throw IllegalStateException("TRANSFER_COMPLETE session ID mismatch")
            }
            // Session fully verified — publish every file now. This keeps failed
            // sessions from leaving partial output in the Downloads folder.
            progressTracker.finishSession()
            completedTargets.forEach(storageManager::completePendingFile)
            // A sender may report success only after durable output has been published.
            FrameUtils.writeTransferAck(outputStream, TransferAckPayload(status = "ACK"))
            completedTargets.clear()

            true
        } catch (e: Exception) {
            failureReason = e.message ?: "File transfer failed"
            activeTargets.forEach { target ->
                storageManager.cleanupTarget(target)
            }
            // Anything already written but not yet finalized is cleaned up too.
            completedTargets.forEach { target ->
                storageManager.cleanupTarget(target)
            }
            Log.e(TAG, "FileReceiver session failed for ${requestPayload.sessionId}: ${e.message}", e)
            false
        }
    }

    private fun readReceiveHeader(input: InputStream): FrameHeader {
        val header = FrameUtils.readHeader(input)
        check(header.tag != FrameTag.TRANSFER_CANCEL) { "Transfer cancelled by sender" }
        return header
    }
}
