package com.dropflow.android.core.model

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat

/** One source of truth for the installed APK, independent of compiled-in constants. */
data class InstalledRelease(val versionName: String, val versionCode: Long, val debuggable: Boolean) {
    companion object {
        @Suppress("DEPRECATION") // Supports the Android 8/9 package-manager API.
        fun read(context: Context): InstalledRelease {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return InstalledRelease(
                versionName = checkNotNull(info.versionName),
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            )
        }
    }
}
