package com.dropflow.android.core.network.transfer

import android.os.SystemClock

class TransferProgressTracker(
    private val sessionId: String,
    private val totalFiles: Int,
    private val totalSizeBytes: Long,
    private val direction: SessionDirection,
    private val onProgressUpdate: (TransferProgressPayload) -> Unit
) {
    private val startTime = SystemClock.elapsedRealtime()
    private var lastEmitTime = 0L

    private var currentFileIndex = 0
    private var currentFileName = ""
    private var currentFileBytes = 0L
    private var currentFileTotalBytes = 0L
    private var sessionBytesTransferred = 0L

    fun startSession(status: String = "Preparing transfer") {
        currentFileName = status
        emitProgress(force = true)
    }

    fun startFile(fileIndex: Int, fileName: String, fileTotalBytes: Long, initialBytes: Long = 0L) {
        currentFileIndex = fileIndex
        currentFileName = fileName
        currentFileBytes = initialBytes.coerceIn(0L, fileTotalBytes)
        currentFileTotalBytes = fileTotalBytes
        sessionBytesTransferred += currentFileBytes
        emitProgress(force = true)
    }

    fun onChunkTransferred(bytesTransferred: Long) {
        currentFileBytes += bytesTransferred
        sessionBytesTransferred += bytesTransferred
        emitProgress(force = false)
    }

    fun finishFile() {
        emitProgress(force = true)
    }

    private fun emitProgress(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastEmitTime < 50)) { // Max 20 FPS updates
            return
        }
        lastEmitTime = now

        val elapsedSecs = ((now - startTime).coerceAtLeast(1)) / 1000.0
        val speedBytesPerSec = if (elapsedSecs > 0) (sessionBytesTransferred / elapsedSecs).toLong() else 0L

        val remainingBytes = (totalSizeBytes - sessionBytesTransferred).coerceAtLeast(0L)
        val etaSeconds = if (speedBytesPerSec > 0) remainingBytes / speedBytesPerSec else 0L

        val percentage = if (totalSizeBytes > 0) {
            ((sessionBytesTransferred.toFloat() / totalSizeBytes.toFloat()) * 100f).coerceIn(0f, 100f)
        } else {
            100f
        }

        val payload = TransferProgressPayload(
            sessionId = sessionId,
            direction = direction,
            currentFileIndex = currentFileIndex,
            currentFileName = currentFileName,
            currentFileBytes = currentFileBytes,
            currentFileTotalBytes = currentFileTotalBytes,
            sessionBytesTransferred = sessionBytesTransferred,
            sessionTotalBytes = totalSizeBytes,
            totalFiles = totalFiles,
            percentage = percentage,
            speedBytesPerSec = speedBytesPerSec,
            etaSeconds = etaSeconds
        )

        onProgressUpdate(payload)
    }
}
