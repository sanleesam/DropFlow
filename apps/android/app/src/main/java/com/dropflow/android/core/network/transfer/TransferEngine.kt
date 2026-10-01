package com.dropflow.android.core.network.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.model.TransferDirection
import com.dropflow.android.core.model.TransferFileItem
import com.dropflow.android.core.model.TransferItem
import com.dropflow.android.core.model.TransferStatus
import com.dropflow.android.core.util.formatBytes
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.pow

private const val TAG = "DropFlowTransferEngine"

class TransferEngine(
    context: Context
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val tcpClient = TcpTransferClient()

    private val sessionsMap = ConcurrentHashMap<String, TransferSession>()
    private val connectionMap = ConcurrentHashMap<String, TransferConnection>()
    private val cancelledSessionIds = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArraySet<TransferEngineListener>()

    private val _activeSessions = MutableStateFlow<List<TransferSession>>(emptyList())
    val activeSessions: StateFlow<List<TransferSession>> = _activeSessions.asStateFlow()

    private val _pendingTransferRequest = MutableStateFlow<TransferRequestPayload?>(null)
    val pendingTransferRequest: StateFlow<TransferRequestPayload?> = _pendingTransferRequest.asStateFlow()

    private val _activeProgress = MutableStateFlow<TransferProgressPayload?>(null)
    val activeProgress: StateFlow<TransferProgressPayload?> = _activeProgress.asStateFlow()

    private val _outgoingTransferActive = MutableStateFlow(false)
    val outgoingTransferActive: StateFlow<Boolean> = _outgoingTransferActive.asStateFlow()

    private val _lastTransferResult = MutableStateFlow<TransferResult?>(null)
    val lastTransferResult: StateFlow<TransferResult?> = _lastTransferResult.asStateFlow()

    private val requestCallbacks = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()

    /**
     * Incoming-transfer authorization policy, mirroring the desktop receiver:
     * when [TransferAuthorizationPolicy.requireConfirmation] is off, or the sender is a
     * trusted device and auto-accept is enabled, requests are accepted without a prompt.
     */
    data class TransferAuthorizationPolicy(
        val requireConfirmation: Boolean = true,
        val autoAcceptTrustedDevices: Boolean = true,
        val trustedDeviceIds: Set<String> = emptySet()
    )

    @Volatile
    private var authorizationPolicy = TransferAuthorizationPolicy()

    fun updateAuthorizationPolicy(
        requireConfirmation: Boolean,
        autoAcceptTrustedDevices: Boolean,
        trustedDeviceIds: Set<String>
    ) {
        authorizationPolicy = TransferAuthorizationPolicy(
            requireConfirmation = requireConfirmation,
            autoAcceptTrustedDevices = autoAcceptTrustedDevices,
            trustedDeviceIds = trustedDeviceIds
        )
        Log.d(TAG, "Authorization policy updated (requireConfirmation=$requireConfirmation, autoAcceptTrusted=$autoAcceptTrustedDevices, trusted=${trustedDeviceIds.size})")
    }

    private val tcpServer = TcpTransferServer(
        context = appContext,
        onRequestReceived = { request, callback ->
            handleIncomingTransferRequest(request, callback)
        },
        onIncomingConnection = { connection ->
            registerConnection(connection)
        },
        onProgressUpdated = { progress ->
            _activeProgress.value = progress
        },
        onTransferFinished = { request, success, error ->
            recordIncomingTransfer(request, success, error)
        }
    )

    val serverStatus: StateFlow<ServerStatus> = tcpServer.status

    fun addListener(listener: TransferEngineListener) {
        listeners += listener
    }

    fun removeListener(listener: TransferEngineListener) {
        listeners -= listener
    }

    fun startServer(port: Int = Protocol.DEFAULT_PORT) {
        scope.launch {
            Log.d(TAG, "TransferEngine starting TCP Server on port $port")
            tcpServer.start(port)
        }
    }

    fun stopServer() {
        Log.d(TAG, "TransferEngine stopping TCP Server")
        tcpServer.stop()
        closeAllSessions()
    }

    /**
     * Initiates an outgoing file transfer to [device] using Android SAF URIs.
     * Resolves display names and sizes via [contentResolver], then runs FileSender
     * over a direct TCP connection. Progress is streamed to [activeProgress].
     */
    fun sendFiles(
        localDeviceId: String,
        localDeviceName: String,
        device: Device,
        uris: List<Uri>,
        contentResolver: ContentResolver
    ) {
        if (uris.isEmpty()) {
            Log.w(TAG, "sendFiles called with empty URI list — ignoring")
            return
        }
        if (_outgoingTransferActive.value) {
            Log.w(TAG, "sendFiles called while outgoing transfer already active — ignoring")
            return
        }

        scope.launch {
            _outgoingTransferActive.value = true
            val sessionId = "tx-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            _lastTransferResult.value = null
            Log.d(TAG, "sendFiles: starting session $sessionId to ${device.name} (${device.addresses.firstOrNull()?.address}:${device.port})")

            // Resolve URI metadata via ContentResolver. Streams are opened up front so
            // a file that disappears mid-selection fails before any bytes are sent.
            val preparedFiles = mutableListOf<Triple<InputStream, Long, String>>() // (stream, size, name)
            val fileMetaList = mutableListOf<FileMetadataPayload>()
            var totalSize = 0L
            var preparationFailed = false

            for ((idx, uri) in uris.withIndex()) {
                val (displayName, fileSize) = resolveUriMeta(contentResolver, uri, idx)
                if (fileSize == null) {
                    Log.e(TAG, "sendFiles: could not determine size of '$displayName'")
                    publishOutgoingFailure(
                        sessionId,
                        device,
                        fileMetaList,
                        "Could not determine the size of '$displayName'"
                    )
                    preparationFailed = true
                    break
                }
                val inputStream = try {
                    contentResolver.openInputStream(uri)
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open URI $uri: ${e.message}", e)
                    null
                }
                if (inputStream == null) {
                    publishOutgoingFailure(sessionId, device, fileMetaList, "Could not open '$displayName'")
                    preparationFailed = true
                    break
                }
                preparedFiles.add(Triple(inputStream, fileSize, displayName))
                fileMetaList.add(
                    FileMetadataPayload(
                        fileIndex = idx,
                        relativePath = displayName,
                        sizeBytes = fileSize,
                        sha256Checksum = "" // will be filled by FileSender after streaming
                    )
                )
                totalSize += fileSize
            }

            if (preparationFailed) {
                preparedFiles.forEach { runCatching { it.first.close() } }
                _outgoingTransferActive.value = false
                return@launch
            }

            val requestPayload = TransferRequestPayload(
                sessionId = sessionId,
                senderId = localDeviceId,
                senderName = localDeviceName,
                senderPlatform = "Android",
                totalFiles = preparedFiles.size,
                totalSizeBytes = totalSize,
                files = fileMetaList
            )

            // Pick the first reachable address
            val targetAddress = device.addresses.firstOrNull()?.address
            if (targetAddress == null) {
                Log.e(TAG, "sendFiles: device ${device.name} has no reachable addresses — aborting")
                preparedFiles.forEach { it.first.close() }
                publishOutgoingFailure(sessionId, device, fileMetaList, "Selected device has no reachable address")
                _outgoingTransferActive.value = false
                return@launch
            }

            val connection = tcpClient.connect(
                context = appContext,
                peerUuid = device.id,
                peerName = device.name,
                host = targetAddress,
                port = device.port,
                sessionId = sessionId,
                onSessionStateChanged = { session -> updateSessionState(session) }
            )

            if (connection == null) {
                Log.e(TAG, "sendFiles: failed to connect to ${device.name}")
                preparedFiles.forEach { it.first.close() }
                publishOutgoingFailure(sessionId, device, fileMetaList, "Could not connect to ${device.name}")
                _outgoingTransferActive.value = false
                return@launch
            }

            registerConnection(connection)

            val socketIn = connection.inputStream
            val socketOut = connection.outputStream
            if (socketIn == null || socketOut == null) {
                Log.e(TAG, "sendFiles: socket streams unavailable after connect")
                preparedFiles.forEach { it.first.close() }
                connection.close()
                publishOutgoingFailure(sessionId, device, fileMetaList, "Socket streams were unavailable")
                _outgoingTransferActive.value = false
                return@launch
            }

            val tracker = TransferProgressTracker(
                sessionId = sessionId,
                totalFiles = preparedFiles.size,
                totalSizeBytes = totalSize,
                direction = SessionDirection.OUTGOING,
                onProgressUpdate = { progress ->
                    _activeProgress.value = progress
                }
            )
            tracker.startSession("Waiting for ${device.name}")

            val sendError: String? = try {
                FileSender().sendTransferSessionFromStreams(
                    socketInputStream = socketIn,
                    socketOutputStream = socketOut,
                    requestPayload = requestPayload,
                    fileStreams = preparedFiles,
                    progressTracker = tracker
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendFiles: FileSender exception: ${e.message}", e)
                e.message ?: "Outgoing transfer failed"
            } finally {
                preparedFiles.forEach { runCatching { it.first.close() } }
            }
            val success = sendError == null

            val cancelled = cancelledSessionIds.remove(sessionId)
            _activeProgress.value = null

            if (success && !cancelled) {
                Log.d(TAG, "sendFiles: session $sessionId completed successfully")
                recordOutgoingTransfer(sessionId, device, fileMetaList, TransferStatus.COMPLETED)
                _lastTransferResult.value = TransferResult(sessionId, "Transfer complete", false)
                connection.updateState(SessionState.DISCONNECTED)
            } else if (cancelled) {
                Log.d(TAG, "sendFiles: session $sessionId cancelled")
                recordOutgoingTransfer(sessionId, device, fileMetaList, TransferStatus.CANCELLED)
                _lastTransferResult.value = TransferResult(sessionId, "Transfer cancelled", false)
            } else {
                Log.e(TAG, "sendFiles: session $sessionId failed: $sendError")
                recordOutgoingTransfer(sessionId, device, fileMetaList, TransferStatus.FAILED, sendError)
                _lastTransferResult.value = TransferResult(sessionId, sendError ?: "Outgoing transfer failed", true)
                connection.updateState(SessionState.FAILED, sendError)
            }
            connection.close()
            _outgoingTransferActive.value = false
        }
    }

    private fun resolveUriMeta(contentResolver: ContentResolver, uri: Uri, fallbackIndex: Int): Pair<String, Long?> {
        var displayName = "file_$fallbackIndex"
        var fileSize: Long? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameCol >= 0) displayName = cursor.getString(nameCol) ?: displayName
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) fileSize = cursor.getLong(sizeCol)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve metadata for URI $uri: ${e.message}")
        }
        if (fileSize == null) {
            fileSize = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                }
            }.getOrNull()
        }
        return displayName to fileSize
    }

    private fun handleIncomingTransferRequest(
        request: TransferRequestPayload,
        callback: (Boolean, String?) -> Unit
    ) {
        val policy = authorizationPolicy
        val isTrusted = policy.trustedDeviceIds.contains(request.senderId)
        val shouldAutoAccept = !policy.requireConfirmation || (isTrusted && policy.autoAcceptTrustedDevices)

        if (shouldAutoAccept) {
            Log.d(TAG, "Auto-accepting incoming request ${request.sessionId} from '${request.senderName}' (trusted=$isTrusted)")
            callback.invoke(true, null)
            return
        }

        Log.d(TAG, "TransferEngine handling incoming request ${request.sessionId} from '${request.senderName}' (${request.totalFiles} files, ${request.totalSizeBytes} bytes)")

        requestCallbacks[request.sessionId] = callback
        _pendingTransferRequest.value = request

        // 60-second authorization timeout guard
        scope.launch {
            delay(60_000)
            if (_pendingTransferRequest.value?.sessionId == request.sessionId) {
                Log.w(TAG, "Transfer request ${request.sessionId} timed out after 60 seconds")
                declinePendingTransfer(request.sessionId, "Request timed out")
            }
        }
    }

    fun acceptPendingTransfer(sessionId: String, trustDevice: Boolean = false) {
        Log.d(TAG, "Accepting transfer request $sessionId (trustDevice: $trustDevice)")
        val callback = requestCallbacks.remove(sessionId)
        callback?.invoke(true, null)

        if (_pendingTransferRequest.value?.sessionId == sessionId) {
            _pendingTransferRequest.value = null
        }
    }

    fun declinePendingTransfer(sessionId: String, reason: String = "User declined transfer") {
        Log.d(TAG, "Declining transfer request $sessionId (reason: $reason)")
        val callback = requestCallbacks.remove(sessionId)
        callback?.invoke(false, reason)

        if (_pendingTransferRequest.value?.sessionId == sessionId) {
            _pendingTransferRequest.value = null
        }
    }

    fun cancelActiveTransfer(sessionId: String) {
        Log.d(TAG, "Cancelling active transfer session $sessionId")
        val connection = connectionMap[sessionId]
            ?: connectionMap.values.firstOrNull { it.transferSessionId == sessionId }
        if (connection != null) {
            cancelledSessionIds += sessionId
            connection.cancelTransfer()
        }
        publishSessions()
    }

    fun closeSession(sessionId: String) {
        connectionMap[sessionId]?.close()
        connectionMap.remove(sessionId)
        sessionsMap.remove(sessionId)
        publishSessions()
    }

    fun closeAllSessions() {
        connectionMap.values.forEach { it.close() }
        connectionMap.clear()
        sessionsMap.clear()
        _activeProgress.value = null
        publishSessions()
    }

    private fun registerConnection(connection: TransferConnection) {
        val session = connection.currentSession.value
        connectionMap[session.sessionId] = connection
        updateSessionState(session)
    }

    private fun updateSessionState(session: TransferSession) {
        if (session.state == SessionState.DISCONNECTED || session.state == SessionState.FAILED) {
            sessionsMap.remove(session.sessionId)
            connectionMap.remove(session.sessionId)
        } else {
            sessionsMap[session.sessionId] = session
        }
        publishSessions()
    }

    private fun publishSessions() {
        _activeSessions.value = sessionsMap.values.toList().sortedByDescending { it.createdAt }
    }

    private fun recordIncomingTransfer(request: TransferRequestPayload, success: Boolean, error: String?) {
        val status = if (success) TransferStatus.COMPLETED else TransferStatus.FAILED
        val item = createTransferItem(
            sessionId = request.sessionId,
            deviceName = request.senderName,
            direction = TransferDirection.RECEIVE,
            files = request.files,
            totalSizeBytes = request.totalSizeBytes,
            status = status,
            error = error
        )
        notifyTransferResult(item)
    }

    private fun recordOutgoingTransfer(
        sessionId: String,
        device: Device,
        files: List<FileMetadataPayload>,
        status: TransferStatus,
        error: String? = null
    ) {
        val item = createTransferItem(
            sessionId = sessionId,
            deviceName = device.name,
            direction = TransferDirection.SEND,
            files = files,
            totalSizeBytes = files.sumOf { it.sizeBytes },
            status = status,
            error = error
        )
        notifyTransferResult(item)
    }

    private fun publishOutgoingFailure(
        sessionId: String,
        device: Device,
        files: List<FileMetadataPayload>,
        error: String
    ) {
        recordOutgoingTransfer(sessionId, device, files, TransferStatus.FAILED, error)
        _lastTransferResult.value = TransferResult(sessionId, error, true)
    }

    private fun createTransferItem(
        sessionId: String,
        deviceName: String,
        direction: TransferDirection,
        files: List<FileMetadataPayload>,
        totalSizeBytes: Long,
        status: TransferStatus,
        error: String?
    ): TransferItem {
        val fileItems = files.map {
            TransferFileItem(it.fileIndex, it.relativePath, it.sizeBytes)
        }
        val fileName = when (files.size) {
            0 -> "Transfer"
            1 -> files.first().relativePath
            else -> "${files.size} files"
        }
        return TransferItem(
            id = sessionId,
            fileName = fileName,
            deviceName = deviceName,
            size = formatBytes(totalSizeBytes),
            timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()),
            direction = direction,
            status = status,
            totalFiles = files.size,
            totalSizeBytes = totalSizeBytes,
            files = fileItems,
            error = error
        )
    }

    private fun notifyTransferResult(item: TransferItem) {
        listeners.forEach { listener ->
            when (item.status) {
                TransferStatus.COMPLETED, TransferStatus.CANCELLED -> listener.onTransferCompleted(item)
                TransferStatus.FAILED -> listener.onTransferFailed(item)
                TransferStatus.IN_PROGRESS -> Unit
            }
        }
    }
}

data class TransferResult(
    val sessionId: String,
    val message: String,
    val isError: Boolean
)
