package com.dropflow.android

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.dropflow.android.core.update.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Device-only updater validation. Never part of a release APK; never launches an installer. */
class UpdateInstrumentation : Instrumentation() {
    private var unsignedPath: String? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        unsignedPath = arguments?.getString("unsignedApk")
        start()
    }
    override fun onStart() {
        val passed = mutableListOf<String>()
        try {
            val apk = File(targetContext.applicationInfo.sourceDir)
            val version = BuildConfig.VERSION_NAME
            val hash = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
            val raw = JSONObject().put("schemaVersion", 1).put("version", version)
                .put("versionCode", BuildConfig.VERSION_CODE).put("packageId", targetContext.packageName)
                .put("url", "https://github.com/sanleesam/DropFlow/releases/download/android-v$version/DropFlow-android-$version.apk")
                .put("sha256", hash).put("size", apk.length())
            val manifest = parseAndroidUpdateManifest(raw.toString(), AndroidUpdateChannel.ALPHA)
            passed += "real Android JSON parser"
            verifyUpdateApk(targetContext, apk, manifest)
            passed += "installed APK signing identity preflight"
            val partial = File.createTempFile("upgrade-preflight-", ".part", targetContext.cacheDir)
            try {
                apk.copyTo(partial, overwrite = true)
                verifyUpdateApk(targetContext, partial, manifest)
                passed += "private .part cache APK parses before installer handoff"
            } finally {
                partial.delete()
            }
            check(runCatching { verifyUpdateApk(targetContext, apk, manifest.copy(versionCode = manifest.versionCode + 1)) }.isFailure)
            passed += "APK metadata mismatch rejected"
            check(runCatching { parseAndroidUpdateManifest(raw.put("versionCode", 18.5).toString(), AndroidUpdateChannel.ALPHA) }.isFailure)
            passed += "fractional install number rejected"
            unsignedPath?.let { path ->
                check(runCatching { verifyUpdateApk(targetContext, File(path), manifest) }.isFailure)
                passed += "unsigned APK rejected"
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("passed", passed.joinToString("; ")) })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", error.toString()); putString("passed", passed.joinToString("; ")) })
        }
    }
}
