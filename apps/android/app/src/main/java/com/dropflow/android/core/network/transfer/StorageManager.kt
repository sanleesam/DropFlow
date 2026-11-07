package com.dropflow.android.core.network.transfer

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

private const val TAG = "DropFlowStorage"
private const val RECEIVE_SUBDIR = "DropFlow"

class StorageManager(private val context: Context) {
    /** Allocate a pending MediaStore row or a uniquely owned legacy staging file. */
    fun prepareTargetStream(fileName: String): WritableTarget {
        val parts = ReceivePath.components(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prepareMediaStoreTarget(parts)
        } else {
            prepareLegacyTarget(parts)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun prepareMediaStoreTarget(parts: List<String>): WritableTarget {
        val resolver = context.contentResolver
        val name = parts.last()
        val folder = (listOf(Environment.DIRECTORY_DOWNLOADS, RECEIVE_SUBDIR) + parts.dropLast(1)).joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        // The provider owns collision handling and allocates separate files.
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create '$name' in Downloads")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open '$name' in Downloads")
            return WritableTarget(ParcelFileDescriptor.AutoCloseOutputStream(descriptor), null, uri, name)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun prepareLegacyTarget(parts: List<String>): WritableTarget {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val dir = File(downloads, (listOf(RECEIVE_SUBDIR) + parts.dropLast(1)).joinToString("/"))
        if (dir.canonicalFile != dir || (!dir.mkdirs() && !dir.isDirectory)) {
            throw IOException("Could not create the receive folder")
        }
        val stage = File.createTempFile(".dropflow-", ".part", dir)
        try {
            return WritableTarget(stage.outputStream(), stage, null, parts.last())
        } catch (error: Exception) {
            stage.delete()
            throw error
        }
    }

    /** Publish already closed and verified data, without replacing another file. */
    fun completePendingFile(target: WritableTarget) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (context.contentResolver.update(checkNotNull(target.uri), values, null, null) != 1) {
                throw IOException("Could not publish '${target.displayName}'")
            }
        } else {
            val stage = checkNotNull(target.stagingFile)
            target.publishedFile = LegacyPublication.publish(stage, target.displayName)
            stage.delete()
        }
    }

    /** Clean up only storage allocated by this transfer. */
    fun cleanupTarget(target: WritableTarget) {
        runCatching { target.stream.close() }
        runCatching {
            target.uri?.let { context.contentResolver.delete(it, null, null) }
            target.stagingFile?.delete()
            target.publishedFile?.delete()
        }.onFailure { Log.w(TAG, "Could not remove transfer output", it) }
    }

    fun isLegacyStoragePermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}

class WritableTarget(
    val stream: FileOutputStream,
    val stagingFile: File?,
    val uri: Uri?,
    val displayName: String
) {
    var publishedFile: File? = null
        internal set
}

internal object LegacyPublication {
    fun publish(stage: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        for (index in 0..9999) {
            val candidate = File(stage.parentFile, if (index == 0) name else
                "${name.substring(0, dot)} ($index)${name.substring(dot)}")
            try {
                // CREATE_NEW is exclusive even if another writer races us.
                Files.createFile(candidate.toPath())
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            try {
                candidate.outputStream().use { output ->
                    stage.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
                return candidate
            } catch (error: Exception) {
                candidate.delete()
                throw error
            }
        }
        throw IOException("Too many files named '$name'")
    }
}
