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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.dropflow.android.storage.TransferHistoryRepository

private const val TAG = "DropFlowTransferEngine"

class TransferEngine(
    context: Context,
    private val history: TransferHistoryRepository
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val tcpClient = TcpTransferClient()

    private val sessionsMap = ConcurrentHashMap<String, TransferSession>()
    private val connectionMap = ConcurrentHashMap<String, TransferConnection>()
    private val transferSlot = TransferSlot()

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

    private data class Approval(val request: TransferRequestPayload, val decision: CompletableDeferred<IncomingDecision>)
    @Volatile
    private var approval: Approval? = null
    @Volatile
    private var requireConfirmation = true

    fun updateAuthorizationPolicy(requireConfirmation: Boolean) {
        this.requireConfirmation = requireConfirmation
    }

    private val tcpServer = TcpTransferServer(
        context = appContext,
        claimTransfer = transferSlot::claim,
        releaseTransfer = transferSlot::release,
        requestDecision = ::decideIncomingRequest,
        onIncomingConnection = { connection ->
            registerConnection(connection)
        },
        onSessionStateChanged = { session -> updateSessionState(session) },
        onProgressUpdated = { progress ->
            _activeProgress.value = progress
        },
        onTransferFinished = ::recordIncomingTransfer
    )

    val serverStatus: StateFlow<ServerStatus> = tcpServer.status

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
        val sessionId = "tx-${UUID.randomUUID()}"
        if (!transferSlot.claim(sessionId)) {
            _lastTransferResult.value = TransferResult(sessionId, "Another transfer is in progress", true)
            return
        }
        _outgoingTransferActive.value = true

        scope.launch {
            try {
                runOutgoingTransfer(sessionId, localDeviceId, localDeviceName, device, uris, contentResolver)
            } finally {
                _activeProgress.value = null
                _outgoingTransferActive.value = false
                transferSlot.release(sessionId)
            }
        }
    }

    private suspend fun runOutgoingTransfer(
        sessionId: String, localDeviceId: String, localDeviceName: String, device: Device,
        uris: List<Uri>, contentResolver: ContentResolver
    ) {
        _lastTransferResult.value = null
        val files = mutableListOf<FileMetadataPayload>()
        var connection: TransferConnection? = null
        var error: String? = null
        try {
            var totalSize = 0L
            for ((index, uri) in uris.withIndex()) {
                val (name, size) = resolveUriMeta(contentResolver, uri, index)
                require(size != null && size >= 0) { "Could not determine the size of '$name'" }
                // An Android SAF display name is one file, not a path.
                val safeName = ReceivePath.components(name.replace('/', '_').replace('\\', '_')).single()
                files += FileMetadataPayload(index, safeName, size, "")
                totalSize = Math.addExact(totalSize, size)
            }
            val request = TransferRequestPayload(sessionId, localDeviceId, localDeviceName, "Android",
                files.size, totalSize, files)
            require(request.toJsonObject().toString().toByteArray(Charsets.UTF_8).size <= Protocol.MAX_METADATA_SIZE) {
                "Too many files for one transfer. Try sending a smaller selection."
            }

            // Try another advertised endpoint only before sending the request.
            for (address in device.addresses.distinctBy { it.address }.take(3)) {
                connection = tcpClient.connect(
                    peerUuid = device.id,
                    peerName = device.name,
                    host = address.address,
                    port = device.port,
                    sessionId = sessionId,
                    onSessionStateChanged = ::updateSessionState
                )
                if (connection != null) break
            }
            val socket = checkNotNull(connection) { "Could not connect to ${device.name}" }
            registerConnection(socket)
            val tracker = TransferProgressTracker(sessionId, files.size, totalSize,
                SessionDirection.OUTGOING) { _activeProgress.value = it }
            tracker.startSession("Waiting for ${device.name}")

            // SAF may return pipes or cloud-backed files. Only open one at a time;
            // keep the selected URI grant, never persist it in transfer history.
            error = FileSender().sendTransferSession(
                socketInputStream = checkNotNull(socket.inputStream),
                socketOutputStream = checkNotNull(socket.outputStream),
                requestPayload = request,
                openFile = { index -> contentResolver.openInputStream(uris[index])
                    ?: throw java.io.IOException("Could not open '${files[index].relativePath}'") },
                progressTracker = tracker,
                setReadTimeout = { socket.socket.soTimeout = it }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Outgoing transfer failed", failure)
            error = failure.message ?: "Outgoing transfer failed"
        } finally {
            connection?.close()
        }

        val cancelled = error != null && connection?.isCancelled == true
        val status = when {
            error == null -> TransferStatus.COMPLETED
            cancelled -> TransferStatus.CANCELLED
            else -> TransferStatus.FAILED
        }
        _activeProgress.value = null
        recordOutgoingTransfer(sessionId, device, files, status, error.takeUnless { cancelled })
        _lastTransferResult.value = TransferResult(sessionId, when (status) {
            TransferStatus.COMPLETED -> "Transfer complete"
            TransferStatus.CANCELLED -> "Transfer cancelled"
            else -> error ?: "Outgoing transfer failed"
        }, status == TransferStatus.FAILED)
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

    private suspend fun decideIncomingRequest(request: TransferRequestPayload): IncomingDecision {
        if (!StorageManager(appContext).isLegacyStoragePermissionGranted()) {
            val error = "Allow storage access in Android settings to receive files"
            _lastTransferResult.value = TransferResult(request.sessionId, error, true)
            return IncomingDecision(false, error)
        }
        _lastTransferResult.value = null
        // DFP/1 does not authenticate sender names or UUIDs.
        if (!requireConfirmation) return IncomingDecision(true)
        val pending = Approval(request, CompletableDeferred())
        approval = pending
        _pendingTransferRequest.value = request
        return try {
            withTimeoutOrNull(60_000) { pending.decision.await() } ?: IncomingDecision(false, "Request timed out")
        } finally {
            approval = null
            _pendingTransferRequest.value = null
            pending.decision.cancel()
        }
    }

    fun respondToRequest(request: TransferRequestPayload, accept: Boolean, reason: String? = null) {
        approval?.takeIf { it.request === request }?.decision?.complete(IncomingDecision(accept, reason))
    }

    fun cancelActiveTransfer(sessionId: String) {
        Log.d(TAG, "Cancelling active transfer session $sessionId")
        val owner = transferSlot.activeId ?: return
        if (_activeProgress.value?.sessionId != sessionId) return
        connectionMap[owner]?.cancelTransfer()
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

    private suspend fun recordIncomingTransfer(request: TransferRequestPayload, success: Boolean, error: String?, cancelledLocally: Boolean) {
        val cancelled = !success && (cancelledLocally || error == "Transfer cancelled by sender")
        val status = when {
            success -> TransferStatus.COMPLETED
            cancelled -> TransferStatus.CANCELLED
            else -> TransferStatus.FAILED
        }
        val item = createTransferItem(
            // History identity is local; a peer cannot overwrite another entry by reusing an ID.
            sessionId = "rx-${UUID.randomUUID()}",
            deviceName = request.senderName,
            direction = TransferDirection.RECEIVE,
            files = request.files,
            totalSizeBytes = request.totalSizeBytes,
            status = status,
            error = if (cancelled) null else error
        )
        recordHistory(item)
        _lastTransferResult.value = TransferResult(request.sessionId, when {
            success -> "Received ${request.totalFiles} ${if (request.totalFiles == 1) "file" else "files"} from ${request.senderName}"
            cancelled -> "Transfer cancelled"
            else -> error ?: "Could not receive files"
        }, !success && !cancelled)
    }

    private suspend fun recordOutgoingTransfer(
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
        recordHistory(item)
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

    private suspend fun recordHistory(item: TransferItem) {
        try {
            history.record(item)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not save transfer history", e)
        }
    }
}

data class TransferResult(
    val sessionId: String,
    val message: String,
    val isError: Boolean
)
