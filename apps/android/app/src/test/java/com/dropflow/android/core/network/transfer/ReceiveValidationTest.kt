package com.dropflow.android.core.network.transfer

import org.junit.Assert.*
import org.junit.Test

class ReceiveValidationTest {
    private val file = FileMetadataPayload(0, "photo.jpg", 12)
    private val request = TransferRequestPayload("session", "peer", "Peer", "Desktop", 1, 12, listOf(file))
    private val header = FileHeaderPayload(0, "photo.jpg", 12)
    private val hash = "a".repeat(64)

    @Test fun validManifestAndHeader() {
        ReceiveValidation.request(request)
        ReceiveValidation.header(request, 0, header)
        ReceiveValidation.checksum("", hash.uppercase(), hash)
    }
    @Test fun emptyFileIsValid() {
        ReceiveValidation.request(request.copy(totalSizeBytes = 0, files = listOf(file.copy(sizeBytes = 0))))
    }
    @Test fun rejectsFileCountMismatch() = rejected { ReceiveValidation.request(request.copy(totalFiles = 2)) }
    @Test fun rejectsNegativeFileSize() = rejected {
        ReceiveValidation.request(request.copy(totalSizeBytes = -1, files = listOf(file.copy(sizeBytes = -1))))
    }
    @Test fun rejectsTotalSizeMismatch() = rejected { ReceiveValidation.request(request.copy(totalSizeBytes = 13)) }
    @Test fun rejectsSizeOverflow() = rejected {
        ReceiveValidation.request(request.copy(totalFiles = 2, files = listOf(file.copy(sizeBytes = Long.MAX_VALUE), file.copy(fileIndex = 1))))
    }
    @Test fun rejectsBlankSession() = rejected { ReceiveValidation.request(request.copy(sessionId = "")) }
    @Test fun rejectsDifferentFilename() = rejected { ReceiveValidation.header(request, 0, header.copy(relativePath = "other.exe")) }
    @Test fun rejectsDifferentSize() = rejected { ReceiveValidation.header(request, 0, header.copy(sizeBytes = 99999)) }
    @Test fun rejectsResumeOffset() = rejected { ReceiveValidation.header(request, 0, header.copy(resumeOffset = 1)) }
    @Test fun rejectsMissingChecksum() = rejected { ReceiveValidation.checksum("", "", hash) }
    @Test fun rejectsWrongChecksum() = rejected { ReceiveValidation.checksum("", "b".repeat(64), hash) }
    @Test fun rejectsChangedDeclaredChecksum() = rejected { ReceiveValidation.checksum("b".repeat(64), hash, hash) }
    @Test fun rejectsInvalidChecksumCharacters() = rejected { ReceiveValidation.checksum("", "z".repeat(64), "z".repeat(64)) }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid metadata to be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: ArithmeticException) { }
    }
}
