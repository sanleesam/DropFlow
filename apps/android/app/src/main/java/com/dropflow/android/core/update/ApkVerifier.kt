package com.dropflow.android.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Preflight checks; Android's installer performs the final signature/upgrade validation. */
@Suppress("DEPRECATION")
fun verifyUpdateApk(context: Context, file: File, manifest: AndroidUpdateManifest) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Downloaded file is not a valid APK")
    val installed = pm.getPackageInfo(context.packageName, flags)
    val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
    require(archive.packageName == context.packageName && archive.versionName == manifest.version && code == manifest.versionCode) {
        "APK identity or version does not match the update"
    }
    fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
    val current = certificates(installed)
    require(current.isNotEmpty() && certificates(archive) == current) {
        "This update uses a different signing key. Debug builds cannot upgrade to published APKs in place."
    }
}
