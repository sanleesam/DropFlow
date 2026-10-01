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

    suspend fun receiveTransferSession(
        inputStream: InputStream,
        outputStream: OutputStream,
        requestPayload: TransferRequestPayload,
        progressTracker: TransferProgressTracker
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting FileReceiver for session ${requestPayload.sessionId} (${requestPayload.totalFiles} files, ${requestPayload.totalSizeBytes} bytes)")
        val activeTargets = mutableListOf<WritableTarget>()
        val completedTargets = mutableListOf<Pair<WritableTarget, Long>>() // target -> bytesWritten

        try {
            for (i in 0 until requestPayload.totalFiles) {
                val header = FrameUtils.readHeader(inputStream)
                if (header.tag != FrameTag.FILE_HEADER) {
                    throw IllegalStateException("Expected FILE_HEADER frame tag, received 0x${String.format("%02X", header.tag.code)}")
                }

                val headerPayloadBytes = FrameUtils.readPayload(inputStream, header.payloadLength)
                val fileHeader = FileHeaderPayload.fromJsonString(String(headerPayloadBytes, StandardCharsets.UTF_8))
                if (fileHeader.fileIndex != i || fileHeader.sizeBytes < 0L) {
                    throw IllegalStateException("Invalid FILE_HEADER for index $i")
                }
                Log.d(TAG, "Receiving file ${i + 1}/${requestPayload.totalFiles}: '${fileHeader.relativePath}' (${fileHeader.sizeBytes} bytes)")

                progressTracker.startFile(i, fileHeader.relativePath, fileHeader.sizeBytes)

                // Send TransferAck (resumeOffset: 0)
                FrameUtils.writeTransferAck(outputStream, TransferAckPayload(status = "ACCEPTED", resumeOffset = 0L))

                // Open the storage target (MediaStore on API 29+, legacy .part file below).
                val target = storageManager.prepareTargetStream(fileHeader.relativePath)
                activeTargets += target

                val messageDigest = MessageDigest.getInstance("SHA-256")
                var bytesWritten = 0L

                target.stream.use { fileOut ->
                    while (bytesWritten < fileHeader.sizeBytes) {
                        val chunkHeader = FrameUtils.readHeader(inputStream)
                        if (chunkHeader.tag == FrameTag.TRANSFER_CANCEL) {
                            throw IllegalStateException("Transfer cancelled by sender")
                        }
                        if (chunkHeader.tag != FrameTag.DATA_CHUNK) {
                            throw IllegalStateException("Expected DATA_CHUNK frame tag, received 0x${String.format("%02X", chunkHeader.tag.code)}")
                        }
                        val remainingBytes = fileHeader.sizeBytes - bytesWritten
                        if (chunkHeader.payloadLength <= 0 || chunkHeader.payloadLength.toLong() > remainingBytes) {
                            throw IllegalStateException("Invalid DATA_CHUNK length ${chunkHeader.payloadLength} for '${fileHeader.relativePath}'")
                        }

                        val chunkBytes = FrameUtils.readPayload(inputStream, chunkHeader.payloadLength)
                        fileOut.write(chunkBytes)
                        messageDigest.update(chunkBytes)

                        bytesWritten += chunkBytes.size
                        progressTracker.onChunkTransferred(chunkBytes.size.toLong())
                    }
                    fileOut.flush()
                }

                progressTracker.finishFile()

                // Read FileComplete frame
                val compHeader = FrameUtils.readHeader(inputStream)
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

                if (fileComp.sha256Checksum.isNotEmpty() && !computedChecksum.equals(fileComp.sha256Checksum, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 checksum mismatch for '${fileHeader.relativePath}'. Deleting corrupted file.")
                    throw IllegalStateException("SHA-256 checksum verification failed")
                }

                // File verified — remember it for finalization once the whole
                // session completes, matching the desktop receiver semantics.
                completedTargets += target to bytesWritten
                activeTargets.remove(target)
                Log.d(TAG, "File '${target.displayName}' received and verified (${bytesWritten} bytes)")
            }

            val finalHeader = FrameUtils.readHeader(inputStream)
            if (finalHeader.tag != FrameTag.TRANSFER_COMPLETE) {
                throw IllegalStateException("Expected TRANSFER_COMPLETE, received ${finalHeader.tag}")
            }
            val completedSessionId = String(FrameUtils.readPayload(inputStream, finalHeader.payloadLength), StandardCharsets.UTF_8)
            if (completedSessionId.isNotBlank() && completedSessionId != requestPayload.sessionId) {
                throw IllegalStateException("TRANSFER_COMPLETE session ID mismatch")
            }
            FrameUtils.writeTransferAck(outputStream, TransferAckPayload(status = "ACK"))

            // Session fully verified — publish every file now. This keeps failed
            // sessions from leaving partial output in the Downloads folder.
            completedTargets.forEach { (target, bytesWritten) ->
                storageManager.completePendingFile(target, bytesWritten)
            }
            completedTargets.clear()

            true
        } catch (e: Exception) {
            activeTargets.forEach { target ->
                storageManager.cleanupTarget(target)
            }
            // Anything already written but not yet finalized is cleaned up too.
            completedTargets.forEach { (target, _) ->
                storageManager.cleanupTarget(target)
            }
            Log.e(TAG, "FileReceiver session failed for ${requestPayload.sessionId}: ${e.message}", e)
            false
        }
    }
}
