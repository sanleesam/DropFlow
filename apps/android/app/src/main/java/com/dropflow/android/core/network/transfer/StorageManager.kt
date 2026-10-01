package com.dropflow.android.core.network.transfer

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

private const val TAG = "DropFlowStorage"
private const val RECEIVE_SUBDIR = "DropFlow"

class StorageManager(private val context: Context) {

    fun getReceiveDirectory(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dropFlowDir = File(publicDownloads, RECEIVE_SUBDIR)
        if (!dropFlowDir.exists()) {
            dropFlowDir.mkdirs()
        }
        return dropFlowDir
    }

    /**
     * Prepares a writable target for the final destination file described by [fileName].
     *
     * On API 29+ this cannot be done with plain java.io.File (scoped storage), so the
     * final file is pre-created in MediaStore and written via [result.outputStream].
     * On API 28 and below the legacy public Downloads path is used directly.
     *
     * Callers finish with [completePendingFile], which finalizes the MediaStore row and
     * frees the pending flag so gallery/media scanners pick the file up.
     */
    fun prepareTargetStream(fileName: String): WritableTarget {
        val sanitized = sanitizeFilename(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prepareMediaStoreTarget(sanitized)
        } else {
            prepareLegacyTarget(sanitized)
        }
    }

    private fun prepareMediaStoreTarget(sanitizedName: String): WritableTarget {
        val resolver = context.contentResolver
        val displayName = sanitizedName.substringAfterLast('/')
        val relativePath = "Download/$RECEIVE_SUBDIR"

        for (attempt in 0 until 10) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            try {
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null for '$displayName'")
                Log.d(TAG, "MediaStore target created for '$displayName' -> $uri")
                return WritableTarget(
                    mode = TargetMode.MEDIA_STORE,
                    stream = resolver.openOutputStream(uri)
                        ?: throw IOException("Could not open MediaStore output stream for '$displayName'"),
                    file = null,
                    uri = uri,
                    displayName = displayName
                )
            } catch (e: java.io.FileNotFoundException) {
                // DISPLAY_NAME already exists: MediaStore appended " (n)" to the
                // display name but openOutputStream can still fail on the conflicting
                // row — retrying inserts a fresh entry that resolves the collision.
                Log.d(TAG, "MediaStore name collision for '$displayName', retrying")
                continue
            }
        }
        throw IOException("Could not create a unique MediaStore entry for '$sanitizedName'")
    }

    private fun prepareLegacyTarget(sanitizedName: String): WritableTarget {
        val dir = getReceiveDirectory()
        val nameOnly = sanitizedName.substringAfterLast('/')
        var target = File(dir, nameOnly)
        if (target.exists()) {
            target = resolveCollision(dir, nameOnly)
        }
        val partFile = preparePartFile(target)
        return WritableTarget(
            mode = TargetMode.LEGACY_FILE,
            stream = partFile.outputStream(),
            file = target,
            uri = null,
            displayName = target.name
        )
    }

    /**
     * Finalizes a completed transfer target: commits the MediaStore row (clearing the
     * pending flag) or renames the legacy .part file onto its final destination.
     * Must be called exactly once per successful file; on failure callers keep the
     * stream open responsibility and simply close it — cleanup happens in FileReceiver.
     */
    fun completePendingFile(target: WritableTarget, totalBytes: Long) {
        target.stream.flush()
        target.stream.close()

        when (target.mode) {
            TargetMode.MEDIA_STORE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        put(MediaStore.MediaColumns.SIZE, totalBytes)
                    }
                    context.contentResolver.update(target.uri!!, values, null, null)
                    Log.d(TAG, "MediaStore entry published: ${target.uri} ($totalBytes bytes)")
                }
            }
            TargetMode.LEGACY_FILE -> {
                val partFile = preparePartFile(target.file!!)
                if (!partFile.renameTo(target.file!!)) {
                    throw IOException("Failed to finalize '${target.displayName}'")
                }
                Log.d(TAG, "Legacy file finalized: ${target.file!!.absolutePath} ($totalBytes bytes)")
            }
        }
    }

    /**
     * Deletes any data written so far for a failed or cancelled transfer.
     */
    fun cleanupTarget(target: WritableTarget) {
        runCatching { target.stream.close() }
        when (target.mode) {
            TargetMode.MEDIA_STORE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        context.contentResolver.delete(target.uri!!, null, null)
                    }.onFailure { Log.w(TAG, "Failed to delete pending MediaStore entry ${target.uri}") }
                }
            }
            TargetMode.LEGACY_FILE -> {
                runCatching {
                    preparePartFile(target.file!!).delete()
                }
            }
        }
    }

    fun preparePartFile(finalFile: File): File {
        return File(finalFile.parentFile, "${finalFile.name}.part")
    }

    fun isLegacyStoragePermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveCollision(dir: File, fileName: String): File {
        val dotIndex = fileName.lastIndexOf('.')
        val nameWithoutExt = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        var counter = 1
        var candidate: File
        do {
            candidate = File(dir, "$nameWithoutExt ($counter)$ext")
            counter++
        } while (candidate.exists())
        return candidate
    }

    private fun sanitizeFilename(fileName: String): String {
        return fileName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }
}

enum class TargetMode { MEDIA_STORE, LEGACY_FILE }

class WritableTarget(
    val mode: TargetMode,
    val stream: java.io.OutputStream,
    val file: File?,
    val uri: Uri?,
    val displayName: String
)
