package com.dropflow.android.core.update

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import org.json.JSONObject

class ChannelNotPublished : Exception("This update channel has no published release yet.")

class AndroidUpdateClient {
    private var activeConnection: HttpURLConnection? = null
    private var cancelled = false

    fun cancel() {
        val connection = synchronized(this) {
            cancelled = true
            activeConnection.also { activeConnection = null }
        }
        connection?.disconnect()
    }

    fun <T> request(address: String, block: (HttpURLConnection) -> T): T {
        var uri = URI(address)
        repeat(6) {
            require(isTrustedUpdateRedirect(uri)) { "Untrusted update redirect" }
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "DropFlow-Android-Updater")
            }
            try {
                synchronized(this) {
                    if (cancelled) throw CancellationException("Update request cancelled")
                    activeConnection = connection
                }
                when (val status = connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        uri = uri.resolve(connection.getHeaderField("Location") ?: error("Missing update redirect"))
                    }
                    404 -> throw ChannelNotPublished()
                    200 -> return block(connection)
                    else -> error("Update service returned HTTP $status. Please try again.")
                }
            } finally {
                connection.disconnect()
                synchronized(this) {
                    if (activeConnection === connection) activeConnection = null
                }
            }
        }
        error("Too many update redirects")
    }

    fun check(channel: AndroidUpdateChannel): AndroidUpdateManifest = request(channel.manifestUrl) { connection ->
        val raw = connection.inputStream.use { input ->
            val bytes = input.readBytesLimited(64 * 1024)
            String(bytes, Charsets.UTF_8)
        }
        parseAndroidUpdateManifest(raw, channel)
    }
}

fun parseAndroidUpdateManifest(raw: String, channel: AndroidUpdateChannel): AndroidUpdateManifest {
    require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Update manifest is too large" }
    val json = JSONObject(raw)
    for (field in listOf("schemaVersion", "versionCode", "size")) {
        require(json.get(field) is Int || json.get(field) is Long) { "Invalid numeric update field" }
    }
    return AndroidUpdateManifest(json.getInt("schemaVersion"), json.getString("version"), json.getLong("versionCode"),
        json.getString("packageId"), json.getString("url"), json.getString("sha256"), json.getLong("size"),
        json.optString("notes", "")).validate(channel)
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "Update manifest is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
