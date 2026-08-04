package com.dropflow.android.core.model

enum class TransferDirection {
    SEND, RECEIVE
}

enum class TransferStatus {
    IN_PROGRESS, COMPLETED, FAILED, CANCELLED
}

data class TransferFileItem(
    val fileIndex: Int,
    val relativePath: String,
    val sizeBytes: Long,
    val finalPath: String? = null
)

data class TransferItem(
    val id: String,
    val fileName: String,
    val deviceName: String,
    val size: String,
    val timestamp: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val direction: TransferDirection,
    val status: TransferStatus,
    val totalFiles: Int = 1,
    val totalSizeBytes: Long = 0L,
    val files: List<TransferFileItem> = emptyList(),
    val error: String? = null
)
