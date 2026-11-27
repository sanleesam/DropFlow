package com.dropflow.android.core.network.transfer

import org.json.JSONArray
import org.json.JSONObject

data class FileMetadataPayload(
    val fileIndex: Int = 0,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256Checksum: String = ""
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("fileIndex", fileIndex)
        put("relativePath", relativePath)
        put("sizeBytes", sizeBytes)
        put("sha256Checksum", sha256Checksum)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): FileMetadataPayload {
            return FileMetadataPayload(
                fileIndex = json.optInt("fileIndex", json.optInt("file_index", 0)),
                relativePath = json.optString("relativePath", json.optString("relative_path", "unknown_file")),
                sizeBytes = json.optLong("sizeBytes", json.optLong("size_bytes", 0L)),
                sha256Checksum = json.optString("sha256Checksum", json.optString("sha256_checksum", ""))
            )
        }
    }
}

data class TransferRequestPayload(
    val sessionId: String,
    val senderId: String,
    val senderName: String,
    val senderPlatform: String,
    val totalFiles: Int,
    val totalSizeBytes: Long,
    val files: List<FileMetadataPayload>,
    /** DFP/1.1 features the sender offers. Older peers ignore the field. */
    val features: List<String> = emptyList()
) {
    val primaryFileName: String
        get() = files.firstOrNull()?.relativePath ?: "File"

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("senderId", senderId)
        put("senderName", senderName)
        put("senderPlatform", senderPlatform)
        put("totalFiles", totalFiles)
        put("totalSizeBytes", totalSizeBytes)
        val filesArray = JSONArray()
        files.forEach { filesArray.put(it.toJsonObject()) }
        put("files", filesArray)
        if (features.isNotEmpty()) put("features", JSONArray(features))
    }

    companion object {
        fun fromJsonString(jsonStr: String): TransferRequestPayload {
            val json = JSONObject(jsonStr)
            val filesList = mutableListOf<FileMetadataPayload>()
            val filesArray = json.optJSONArray("files")
            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    filesList.add(FileMetadataPayload.fromJsonObject(filesArray.getJSONObject(i)))
                }
            }

            return TransferRequestPayload(
                sessionId = json.optString("sessionId", json.optString("session_id", "")),
                senderId = json.optString("senderId", json.optString("sender_id", "")),
                senderName = json.optString("senderName", json.optString("sender_name", "Unknown Peer")),
                senderPlatform = json.optString("senderPlatform", json.optString("sender_platform", "Desktop")),
                totalFiles = json.optInt("totalFiles", json.optInt("total_files", filesList.size)),
                totalSizeBytes = json.optLong("totalSizeBytes", json.optLong("total_size_bytes", 0L)),
                files = filesList,
                features = featureNames(json, strict = true)
            )
        }
    }
}

data class FileHeaderPayload(
    val fileIndex: Int,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256Checksum: String = "",
    val resumeOffset: Long = 0L
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("fileIndex", fileIndex)
        put("relativePath", relativePath)
        put("sizeBytes", sizeBytes)
        put("sha256Checksum", sha256Checksum)
        put("resumeOffset", resumeOffset)
    }

    companion object {
        fun fromJsonString(jsonStr: String): FileHeaderPayload {
            val json = JSONObject(jsonStr)
            return FileHeaderPayload(
                fileIndex = json.optInt("fileIndex", json.optInt("file_index", 0)),
                relativePath = json.optString("relativePath", json.optString("relative_path", "")),
                sizeBytes = json.optLong("sizeBytes", json.optLong("size_bytes", 0L)),
                sha256Checksum = json.optString("sha256Checksum", json.optString("sha256_checksum", "")),
                resumeOffset = json.optLong("resumeOffset", json.optLong("resume_offset", 0L))
            )
        }
    }
}

data class FileCompletePayload(
    val fileIndex: Int,
    val bytesWritten: Long,
    val sha256Checksum: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("fileIndex", fileIndex)
        put("bytesWritten", bytesWritten)
        put("sha256Checksum", sha256Checksum)
    }

    companion object {
        fun fromJsonString(jsonStr: String): FileCompletePayload {
            val json = JSONObject(jsonStr)
            return FileCompletePayload(
                fileIndex = json.optInt("fileIndex", json.optInt("file_index", 0)),
                bytesWritten = json.optLong("bytesWritten", json.optLong("bytes_written", 0L)),
                sha256Checksum = json.optString("sha256Checksum", json.optString("sha256_checksum", ""))
            )
        }
    }
}

data class TransferAckPayload(
    val status: String = "ACCEPTED",
    val resumeOffset: Long = 0L
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("status", status)
        put("resumeOffset", resumeOffset)
    }

    companion object {
        fun fromJsonString(jsonStr: String): TransferAckPayload {
            val json = JSONObject(jsonStr)
            return TransferAckPayload(
                status = json.optString("status", "ACCEPTED"),
                resumeOffset = json.optLong("resumeOffset", json.optLong("resume_offset", 0L))
            )
        }
    }
}

data class TransferAcceptPayload(
    val status: String = "ACCEPTED",
    val resumeOffset: Long = 0L,
    /** DFP/1.1 features the receiver grants. Empty keeps the DFP/1 reply unchanged. */
    val features: List<String> = emptyList()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("status", status)
        if (features.isEmpty()) put("resumeOffset", resumeOffset) else put("features", JSONArray(features))
    }

    companion object {
        fun fromJsonString(jsonStr: String): TransferAcceptPayload {
            val json = JSONObject(jsonStr)
            return TransferAcceptPayload(
                status = json.optString("status", "ACCEPTED"),
                resumeOffset = json.optLong("resumeOffset", json.optLong("resume_offset", 0L)),
                features = featureNames(json, strict = false)
            )
        }

        /** Older receivers answer with plain text or JSON without features: nothing granted. */
        fun grantedFeatures(payload: ByteArray): List<String> =
            runCatching { featureNames(JSONObject(String(payload, Charsets.UTF_8)), strict = false) }.getOrDefault(emptyList())
    }
}

/**
 * Lenient about shape: anything that is not a list of names counts as no
 * features. The list itself is bounded. A request ([strict]) outside the
 * bounds is rejected; a reply just has the excess ignored.
 */
private fun featureNames(json: JSONObject, strict: Boolean): List<String> {
    val array = json.optJSONArray("features") ?: return emptyList()
    if (strict) require(array.length() <= Protocol.MAX_FEATURES) { "Too many feature names" }
    val names = ArrayList<String>()
    for (index in 0 until minOf(array.length(), Protocol.MAX_FEATURES)) {
        val name = array.opt(index) as? String ?: continue
        val fits = name.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_FEATURE_NAME_BYTES
        if (strict) require(fits) { "Feature name is too long" }
        if (fits) names += name
    }
    return names
}

/** One staged file the receiver can continue, with the SHA-256 of exactly the bytes it holds. */
data class ResumeEntry(
    val fileIndex: Int,
    val offset: Long,
    val sha256: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("i", fileIndex)
        put("o", offset)
        put("h", sha256)
    }

    companion object {
        fun listFromJsonString(jsonStr: String): List<ResumeEntry> {
            val array = JSONArray(jsonStr)
            return (0 until array.length()).map { index ->
                val json = array.getJSONObject(index)
                ResumeEntry(json.getInt("i"), json.getLong("o"), json.getString("h"))
            }
        }
    }
}

data class TransferRejectPayload(
    val status: String = "REJECTED",
    val reason: String = "User declined transfer"
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("status", status)
        put("reason", reason)
    }

    companion object {
        fun fromJsonString(jsonStr: String): TransferRejectPayload {
            val json = JSONObject(jsonStr)
            return TransferRejectPayload(
                status = json.optString("status", "REJECTED"),
                reason = json.optString("reason", "User declined transfer")
            )
        }
    }
}

enum class TransferPhase { PREPARING, STREAMING, FINISHING }

data class TransferProgressPayload(
    val sessionId: String,
    val direction: SessionDirection,
    val currentFileIndex: Int,
    val currentFileName: String,
    val currentFileBytes: Long,
    val currentFileTotalBytes: Long,
    val sessionBytesTransferred: Long,
    val sessionTotalBytes: Long,
    val totalFiles: Int,
    val percentage: Float,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val phase: TransferPhase = TransferPhase.STREAMING
)
