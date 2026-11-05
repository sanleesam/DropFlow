package com.dropflow.android.core.update

import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest

enum class AndroidUpdateChannel(val id: String, val title: String, val description: String) {
    ALPHA("alpha", "Alpha", "Experimental builds and early testing"),
    BETA("beta", "Beta", "Preview releases and release candidates"),
    STABLE("stable", "Stable", "Normal releases, when available");

    val manifestUrl get() = "https://github.com/sanleesam/DropFlow/releases/download/android-$id/latest.json"
    fun accepts(version: SemanticVersion) = when (this) {
        ALPHA -> version.stage == "alpha" || version.stage == "dev"
        BETA -> version.stage == "beta" || version.stage == "rc"
        STABLE -> version.stage.isEmpty()
    }
    companion object {
        fun forVersion(version: String) = entries.firstOrNull { it.accepts(SemanticVersion.parse(version)) } ?: ALPHA
        fun fromId(id: String, version: String) = entries.firstOrNull { it.id == id } ?: forVersion(version)
    }
}

data class SemanticVersion(val core: List<BigInteger>, val stage: String, val counter: BigInteger) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for (i in 0..2) core[i].compareTo(other.core[i]).takeIf { it != 0 }?.let { return it }
        if (stage.isEmpty() || other.stage.isEmpty()) return when {
            stage == other.stage -> 0
            stage.isEmpty() -> 1
            else -> -1
        }
        stage.compareTo(other.stage).takeIf { it != 0 }?.let { return it }
        return counter.compareTo(other.counter)
    }
    companion object {
        private val pattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-(alpha|beta|rc|dev)\\.(0|[1-9]\\d*))?$")
        fun parse(value: String): SemanticVersion {
            require(value.length <= 128) { "Invalid update version" }
            val match = pattern.matchEntire(value) ?: error("Invalid update version")
            return SemanticVersion((1..3).map { match.groupValues[it].toBigInteger() },
                match.groupValues[4], match.groupValues[5].ifEmpty { "0" }.toBigInteger())
        }
    }
}

data class AndroidUpdateManifest(
    val schemaVersion: Int, val version: String, val versionCode: Long,
    val packageId: String, val url: String, val sha256: String, val size: Long,
    val notes: String = ""
) {
    fun validate(channel: AndroidUpdateChannel): AndroidUpdateManifest {
        require(schemaVersion == 1) { "Unsupported Android update format" }
        require(channel.accepts(SemanticVersion.parse(version))) { "Update does not belong to this channel" }
        require(versionCode in 1..2_100_000_000L) { "Invalid update install number" }
        require(packageId == "com.dropflow.android") { "Update belongs to another app" }
        require(url == "https://github.com/sanleesam/DropFlow/releases/download/android-v$version/DropFlow-android-$version.apk") { "Untrusted update download address" }
        require(Regex("^[a-f0-9]{64}$").matches(sha256)) { "Invalid update checksum" }
        require(size in 1..MAX_APK_SIZE) { "Invalid update size" }
        require(notes.length <= 8000) { "Update notes are too large" }
        return this
    }

    fun isNewerThan(currentVersion: String, currentCode: Long): Boolean =
        versionCode > currentCode && SemanticVersion.parse(version) > SemanticVersion.parse(currentVersion)
}

const val MAX_APK_SIZE = 256L * 1024 * 1024

fun isTrustedUpdateRedirect(uri: URI): Boolean = uri.scheme == "https" && uri.userInfo == null &&
    uri.port in listOf(-1, 443) && uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal fun copyVerifiedUpdate(input: InputStream, output: OutputStream, manifest: AndroidUpdateManifest,
    onProgress: (Long) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        require(total <= manifest.size && total <= MAX_APK_SIZE) { "Update exceeds its declared size" }
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        onProgress(total)
    }
    require(total == manifest.size) { "Update download is incomplete" }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    require(hash == manifest.sha256) { "Update checksum did not match. Please try again." }
}
