package com.dropflow.android.core.network.transfer

internal object ReceivePath {
    fun components(path: String): List<String> {
        require(path.length <= 1024 && '\\' !in path) { "Invalid relative file path" }
        val parts = path.split('/')
        require(parts.size <= 20) { "File path is too deeply nested" }
        return parts.map { part ->
            require(part.isNotBlank() && part != "." && part != ".." &&
                part.none { it.isISOControl() || it == ':' }) { "Invalid relative file path" }
            val safe = part.map { if (it in "*?\"<>|") '_' else it }.joinToString("").trim().trimEnd('.')
            require(safe.isNotBlank() && safe.toByteArray(Charsets.UTF_8).size <= 240) { "Invalid file or folder name" }
            safe
        }
    }
}
