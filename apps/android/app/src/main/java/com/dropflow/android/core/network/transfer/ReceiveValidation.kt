package com.dropflow.android.core.network.transfer

/** Validates the accepted manifest before any storage allocation. DFP/1 stays unchanged. */
internal object ReceiveValidation {
    fun request(request: TransferRequestPayload) {
        require(request.sessionId.isNotBlank() && request.sessionId.length <= 128) { "Invalid transfer session ID" }
        require(request.totalFiles > 0 && request.totalFiles == request.files.size) { "Invalid file count" }
        var total = 0L
        request.files.forEachIndexed { index, file ->
            require(file.fileIndex == index && file.sizeBytes >= 0 && file.relativePath.isNotBlank()) {
                "Invalid file metadata at index $index"
            }
            ReceivePath.components(file.relativePath)
            total = Math.addExact(total, file.sizeBytes)
        }
        require(total == request.totalSizeBytes) { "Transfer size does not match file metadata" }
    }

    fun header(request: TransferRequestPayload, index: Int, header: FileHeaderPayload) {
        val expected = request.files[index]
        require(header.fileIndex == index && header.relativePath == expected.relativePath &&
            header.sizeBytes == expected.sizeBytes && header.resumeOffset == 0L) {
            "File header does not match the accepted transfer"
        }
        require(expected.sha256Checksum.isEmpty() ||
            expected.sha256Checksum.equals(header.sha256Checksum, ignoreCase = true)) {
            "File checksum does not match the accepted transfer"
        }
    }

    fun checksum(expected: String, completed: String, computed: String) {
        require(completed.length == 64 && completed.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } &&
            completed.equals(computed, ignoreCase = true) &&
            (expected.isEmpty() || expected.equals(computed, ignoreCase = true))) {
            "SHA-256 checksum verification failed"
        }
    }
}
