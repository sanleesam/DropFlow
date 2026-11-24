package com.dropflow.android.core.network.transfer

import java.util.Locale

/** One-line timing for benchmarks, matching the desktop `[Perf]` line. Local logs only. */
internal object TransferPerf {
    fun summary(direction: String, files: Int, bytes: Long, elapsedNanos: Long): String {
        val secs = elapsedNanos / 1e9
        val mibS = if (secs > 0) bytes / 1048576.0 / secs else 0.0
        val perFile = if (files > 0) secs * 1000 / files else 0.0
        return String.format(
            Locale.ROOT,
            "[Perf] %s files=%d bytes=%d secs=%.3f mib_s=%.2f ms_per_file=%.1f",
            direction, files, bytes, secs, mibS, perFile
        )
    }
}
