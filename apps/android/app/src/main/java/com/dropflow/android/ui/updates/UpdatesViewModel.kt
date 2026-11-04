package com.dropflow.android.ui.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dropflow.android.BuildConfig
import com.dropflow.android.core.update.*
import com.dropflow.android.storage.UserPreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, UNAVAILABLE, AVAILABLE, DOWNLOADING, READY, VERIFYING, PERMISSION_REQUIRED, INSTALLER_OPEN, ERROR }
data class UpdatesUiState(
    val channel: AndroidUpdateChannel = AndroidUpdateChannel.forVersion(BuildConfig.VERSION_NAME),
    val phase: UpdatePhase = UpdatePhase.IDLE, val manifest: AndroidUpdateManifest? = null,
    val downloaded: Long = 0, val message: String? = null, val checkedAt: Long? = null
)

class UpdatesViewModel(context: Context, private val preferences: UserPreferencesRepository) : ViewModel() {
    private val app = context.applicationContext
    private val client = AndroidUpdateClient()
    private val _state = MutableStateFlow(UpdatesUiState())
    val state = _state.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    private var readyApk: File? = null

    init {
        viewModelScope.launch {
            preferences.userPreferencesFlow.collect { prefs ->
                val channel = AndroidUpdateChannel.fromId(prefs.updateChannel, BuildConfig.VERSION_NAME)
                if (channel != _state.value.channel) reset(channel)
            }
        }
    }

    private fun reset(channel: AndroidUpdateChannel = _state.value.channel) {
        generation++
        operation?.cancel()
        client.cancel()
        readyApk?.delete()
        readyApk = null
        _state.value = UpdatesUiState(channel)
    }

    fun selectChannel(channel: AndroidUpdateChannel) {
        if (channel == _state.value.channel) return
        reset(channel)
        viewModelScope.launch { preferences.updateChannel(channel.id) }
    }

    fun check() {
        reset()
        val token = generation
        val channel = _state.value.channel
        _state.update { it.copy(phase = UpdatePhase.CHECKING) }
        operation = viewModelScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { client.check(channel) }
                if (token != generation) return@launch
                _state.update { it.copy(manifest = manifest, checkedAt = System.currentTimeMillis(),
                    phase = if (manifest.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()))
                        UpdatePhase.AVAILABLE else UpdatePhase.CURRENT) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: ChannelNotPublished) {
                if (token == generation) _state.update { it.copy(phase = UpdatePhase.UNAVAILABLE, checkedAt = System.currentTimeMillis()) }
            } catch (error: Exception) {
                if (token == generation) _state.update { it.copy(phase = UpdatePhase.ERROR, message = updateErrorMessage(error)) }
            }
        }
    }

    fun download() {
        val manifest = _state.value.manifest ?: return
        if (_state.value.phase !in listOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) return
        if (!manifest.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) return
        readyApk?.delete()
        readyApk = null
        val token = generation
        _state.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, message = null) }
        operation = viewModelScope.launch {
            val directory = File(app.cacheDir, "updates")
            val cacheId = java.util.UUID.randomUUID().toString()
            val partial = File(directory, "${manifest.version}-$cacheId.part")
            val target = File(directory, "${manifest.version}-$cacheId.apk")
            try {
                withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "Could not create update cache" }
                    client.request(manifest.url) { connection ->
                        connection.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                copyVerifiedUpdate(input, output, manifest) { bytes ->
                                    ensureActive()
                                    if (token == generation) _state.update { it.copy(downloaded = bytes) }
                                }
                            }
                        }
                    }
                    ensureActive()
                    verifyUpdateApk(app, partial, manifest)
                    if (target.exists()) check(target.delete()) { "Could not replace update cache" }
                    check(partial.renameTo(target)) { "Could not save verified update" }
                    check(target.setReadOnly()) { "Could not protect verified update" }
                }
                if (token == generation) {
                    readyApk = target
                    _state.update { it.copy(phase = UpdatePhase.READY) }
                } else target.delete()
            } catch (cancelled: CancellationException) { target.delete(); throw cancelled
            } catch (error: Exception) {
                target.delete()
                if (token == generation) _state.update { it.copy(phase = UpdatePhase.ERROR, message = updateErrorMessage(error)) }
            } finally { partial.delete() }
        }
    }

    fun cancelDownload() {
        val manifest = _state.value.manifest
        val checkedAt = _state.value.checkedAt
        reset()
        _state.update { it.copy(phase = if (manifest == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
            manifest = manifest, checkedAt = checkedAt, message = "Download cancelled") }
    }

    fun install(context: Context) {
        if (_state.value.phase !in listOf(UpdatePhase.READY, UpdatePhase.PERMISSION_REQUIRED, UpdatePhase.INSTALLER_OPEN)) return
        val apk = readyApk ?: return
        val manifest = _state.value.manifest ?: return
        _state.update { it.copy(phase = UpdatePhase.VERIFYING) }
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Recheck cached bytes and identity immediately before handing them to Android.
                    apk.inputStream().use { copyVerifiedUpdate(it, object : java.io.OutputStream() {
                        override fun write(value: Int) {}
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
                    }, manifest) {} }
                    verifyUpdateApk(app, apk, manifest)
                }
                if (!app.packageManager.canRequestPackageInstalls()) {
                    _state.update { it.copy(phase = UpdatePhase.PERMISSION_REQUIRED) }
                    return@launch
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                _state.update { it.copy(phase = UpdatePhase.INSTALLER_OPEN) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(phase = UpdatePhase.ERROR, message = updateErrorMessage(error)) }
            }
        }
    }

    fun openInstallSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))) }
            .onFailure { error -> _state.update { it.copy(phase = UpdatePhase.ERROR, message = error.message) } }
    }

    override fun onCleared() { operation?.cancel(); client.cancel(); super.onCleared() }
    companion object {
        fun factory(context: Context, preferences: UserPreferencesRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdatesViewModel(context.applicationContext, preferences) }
        }
    }
}
