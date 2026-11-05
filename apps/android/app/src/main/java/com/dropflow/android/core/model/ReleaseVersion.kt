package com.dropflow.android.core.model

fun releaseLabel(version: String, debug: Boolean): String {
    val stage = version.substringAfter('-', "").substringBefore('.')
    val label = when (stage) {
        "alpha" -> "Alpha"
        "beta" -> "Beta"
        "rc" -> "Release candidate"
        "dev" -> "Development"
        "" -> "Release"
        else -> "Preview"
    }
    return if (debug) "$label · Debug build" else label
}
