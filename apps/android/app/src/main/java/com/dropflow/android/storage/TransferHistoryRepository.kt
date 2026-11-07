package com.dropflow.android.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropflow.android.core.model.TransferDirection
import com.dropflow.android.core.model.TransferFileItem
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.model.TransferStatus
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyStore by preferencesDataStore(name = "transfer_history")

/** App-private terminal history; never stores transferable permissions or file contents. */
class TransferHistoryRepository(context: Context) {
    private val store = context.applicationContext.historyStore
    private val key = stringPreferencesKey("entries_v1")

    val entries = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { decode(it[key]) }

    suspend fun record(item: TransferItem) {
        if (item.status == TransferStatus.IN_PROGRESS) return
        store.edit { prefs ->
            val previous = decode(prefs[key]).filterNot { it.id == item.id }
            prefs[key] = JSONArray((listOf(item) + previous).take(100).map(::encode)).toString()
        }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }

    private fun encode(item: TransferItem) = JSONObject().apply {
        put("id", item.id)
        put("name", item.fileName.take(512))
        put("peer", item.deviceName.take(120))
        put("size", item.size)
        put("date", item.timestamp)
        put("time", item.timestampMs)
        put("direction", item.direction.name)
        put("status", item.status.name)
        put("count", item.totalFiles)
        put("bytes", item.totalSizeBytes)
        put("error", item.error?.take(300))
        put("files", JSONArray(item.files.take(24).map { file ->
            JSONObject().put("index", file.fileIndex).put("name", file.relativePath.take(512))
                .put("size", file.sizeBytes)
        }))
    }

    private fun decode(value: String?): List<TransferItem> {
        val entries = runCatching { JSONArray(value ?: "[]") }.getOrElse { return emptyList() }
        return (0 until entries.length().coerceAtMost(100)).mapNotNull { index ->
            runCatching {
                val item = entries.getJSONObject(index)
                val files = item.getJSONArray("files")
                TransferItem(
                    id = item.getString("id"),
                    fileName = item.getString("name"),
                    deviceName = item.getString("peer"),
                    size = item.getString("size"),
                    timestamp = item.getString("date"),
                    timestampMs = item.getLong("time"),
                    direction = TransferDirection.valueOf(item.getString("direction")),
                    status = TransferStatus.valueOf(item.getString("status")),
                    totalFiles = item.getInt("count"),
                    totalSizeBytes = item.getLong("bytes"),
                    files = (0 until files.length().coerceAtMost(24)).map {
                        val file = files.getJSONObject(it)
                        TransferFileItem(file.getInt("index"), file.getString("name"), file.getLong("size"))
                    },
                    error = item.optString("error").ifBlank { null }
                )
            }.getOrNull()
        }
    }
}
