package com.dropflow.android.core.util

import kotlin.math.ln
import kotlin.math.pow

// Human-readable formatting shared by the transfer engine and the UI layers.

/** Formats a byte count as a human-readable string, e.g. "1.5 MB". */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val unitIndex = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.lastIndex)
    return String.format("%.1f %s", bytes / 1024.0.pow(unitIndex), units[unitIndex])
}

/** Formats a transfer speed in bytes per second, e.g. "2.3 MB/s". */
fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    val unitIndex = (ln(bytesPerSec.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.lastIndex)
    return String.format("%.1f %s", bytesPerSec / 1024.0.pow(unitIndex), units[unitIndex])
}
