package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val TAG = "DropFlowConnection"

internal class TransferConnection(
    val socket: Socket,
    val session: TransferSession,
    private val onSessionStateChanged: (TransferSession) -> Unit,
    private val claimTransfer: ((String) -> Boolean)? = null,
    private val releaseTransfer: ((String) -> Unit)? = null,
    private val requestDecision: (suspend (TransferRequestPayload) -> IncomingDecision)? = null,
    private val onProgressUpdated: ((TransferProgressPayload?) -> Unit)? = null,
    private val onTransferFinished: (suspend (TransferRequestPayload, Boolean, String?, Boolean) -> Unit)? = null
) {
    private val closed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val noticeSent = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()

    private var sessionInput: SessionInput? = null
    private var frameOutput: FrameOutput? = null

    var inputStream: InputStream? = null
        private set
    var outputStream: OutputStream? = null
        private set

    /** The limit for one read. The socket itself wakes more often to notice a cancel. */
    var readTimeoutMs: Int
        get() = sessionInput?.timeoutMs ?: Protocol.READ_TIMEOUT_MS
        set(value) { sessionInput?.timeoutMs = value }

    private val _currentSession = MutableStateFlow(session)
    val currentSession: StateFlow<TransferSession> = _currentSession.asStateFlow()

    @Volatile
    var transferSessionId: String? = null
        private set

    init {
        try {
            socket.soTimeout = SessionInput.SLICE_MS
            // Request/response control frames must not wait on Nagle + delayed ACK
            // (up to 200 ms per file on Windows peers). Data frames are large writes.
            socket.tcpNoDelay = true
            val input = SessionInput(socket.getInputStream(), cancelled::get)
            sessionInput = input
            // Batch mode sends many small frames back to back; read them in bulk.
            inputStream = BufferedInputStream(input, 64 * 1024)
            outputStream = FrameOutput(socket.getOutputStream()).also { frameOutput = it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize socket streams for session ${session.sessionId}", e)
            updateState(SessionState.FAILED, e.message)
        }
    }

    fun updateState(newState: SessionState, error: String? = null) {
        val updated = _currentSession.value.copy(
            state = newState,
            errorMessage = error
        )
        _currentSession.value = updated
        onSessionStateChanged(updated)
    }

    suspend fun performOutgoingHandshake(): Boolean = withContext(Dispatchers.IO) {
        try {
            updateState(SessionState.CONNECTING)
            val out = outputStream ?: return@withContext false

            Log.d(TAG, "Sending client handshake HELLO for session ${session.sessionId}")
            FrameUtils.writeHeartbeat(out, Protocol.HELLO_HANDSHAKE)

            val inp = inputStream ?: return@withContext false
            val header = FrameUtils.readHeader(inp)
            if (header.tag != FrameTag.HEARTBEAT) {
                updateState(SessionState.FAILED, "Expected handshake heartbeat")
                return@withContext false
            }
            val ackBytes = FrameUtils.readPayload(inp, header.payloadLength)
            val ackString = String(ackBytes, StandardCharsets.UTF_8).trim()

            if (ackString == Protocol.HELLO_ACK) {
                Log.d(TAG, "Handshake successful for session ${session.sessionId}")
                updateState(SessionState.CONNECTED)
                true
            } else {
                Log.e(TAG, "Handshake failed: unexpected ACK '$ackString'")
                updateState(SessionState.FAILED, "Invalid handshake response")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake exception for session ${session.sessionId}", e)
            updateState(SessionState.FAILED, e.message)
            false
        }
    }

    suspend fun performIncomingHandshake(): Boolean = withContext(Dispatchers.IO) {
        try {
            updateState(SessionState.CONNECTING)
            val inp = inputStream ?: return@withContext false

            Log.d(TAG, "Waiting for incoming handshake HELLO on session ${session.sessionId}")
            val header = FrameUtils.readHeader(inp)
            if (header.tag != FrameTag.HEARTBEAT) {
                updateState(SessionState.FAILED, "Expected handshake heartbeat")
                return@withContext false
            }
            val helloBytes = FrameUtils.readPayload(inp, header.payloadLength)
            val helloString = String(helloBytes, StandardCharsets.UTF_8).trim()

            if (helloString == Protocol.HELLO_HANDSHAKE) {
                val out = outputStream ?: return@withContext false
                Log.d(TAG, "Sending server handshake HELLO_ACK for session ${session.sessionId}")
                FrameUtils.writeHeartbeat(out, Protocol.HELLO_ACK)

                updateState(SessionState.CONNECTED)
                true
            } else {
                Log.e(TAG, "Incoming handshake failed: expected HELLO, received '$helloString'")
                updateState(SessionState.FAILED, "Invalid client handshake")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Incoming handshake exception for session ${session.sessionId}", e)
            updateState(SessionState.FAILED, e.message)
            false
        }
    }

    suspend fun startListening(context: android.content.Context) {
        withContext(Dispatchers.IO) {
            try {
                val inp = inputStream ?: return@withContext
                val out = outputStream ?: return@withContext

                while (_currentSession.value.state == SessionState.CONNECTED) {
                    val header = FrameUtils.readHeader(inp)
                    // Only a request may be large here; nothing has been approved yet.
                    check(header.tag != FrameTag.RESUME_STATE) { "Unexpected RESUME_STATE frame" }
                    val payloadBytes = FrameUtils.readPayload(inp, header.payloadLength)
                    Log.d(TAG, "Received frame ${header.tag} len ${header.payloadLength} on session ${session.sessionId}")

                    when (header.tag) {
                        FrameTag.TRANSFER_REQUEST, FrameTag.TRANSFER_REQUEST_LARGE -> {
                            val requestPayload = TransferRequestPayload.fromJsonString(String(payloadBytes, StandardCharsets.UTF_8))
                            ReceiveValidation.request(requestPayload)
                            receiveRequest(context, inp, out, requestPayload)
                            return@withContext
                        }
                        FrameTag.HEARTBEAT -> {
                            Log.d(TAG, "Heartbeat frame received")
                        }
                        FrameTag.TRANSFER_CANCEL -> {
                            Log.d(TAG, "Transfer cancelled by peer")
                            updateState(SessionState.CLOSING)
                            break
                        }
                        else -> {
                            Log.d(TAG, "Frame ${header.tag} received")
                        }
                    }
                }
            } catch (e: Exception) {
                if (_currentSession.value.state == SessionState.CONNECTED) {
                    Log.w(TAG, "Connection lost on session ${session.sessionId}: ${e.message}")
                    updateState(SessionState.FAILED, e.message)
                }
            } finally {
                close()
            }
        }
    }

    private suspend fun receiveRequest(
        context: android.content.Context,
        input: InputStream,
        output: OutputStream,
        request: TransferRequestPayload
    ) {
        val decide = requestDecision ?: return
        if (claimTransfer?.invoke(session.sessionId) != true) {
            FrameUtils.writeTransferReject(output, TransferRejectPayload("REJECTED", "Busy: another transfer is in progress"))
            return
        }
        transferSessionId = request.sessionId
        try {
            val decision = decide(request)
            if (!decision.accepted) {
                FrameUtils.writeTransferReject(output, TransferRejectPayload("REJECTED", decision.reason ?: "User declined transfer"))
                return
            }
            val receiver = FileReceiver(StorageManager(context))
            val tracker = TransferProgressTracker(
                sessionId = request.sessionId,
                totalFiles = request.totalFiles,
                totalSizeBytes = request.totalSizeBytes,
                direction = SessionDirection.INCOMING,
                onProgressUpdate = { progress -> onProgressUpdated?.invoke(progress) }
            )
            val error = try {
                tracker.startSession("Preparing to receive")
                // The receiver sends the acceptance, with the features it grants.
                val received = receiver.receiveTransferSession(
                    input, output, request, tracker,
                    isCancelled = cancelled::get,
                    sendNotice = { tag, payload -> sendNotice(tag, payload, drain = true) }
                )
                if (received) null else receiver.failureReason ?: "File transfer failed"
            } catch (e: Exception) {
                Log.w(TAG, "Receive failed for ${session.sessionId}", e)
                e.message ?: "File transfer failed"
            }
            onProgressUpdated?.invoke(null)
            onTransferFinished?.invoke(request, error == null, error, isCancelled)
        } finally {
            releaseTransfer?.invoke(session.sessionId)
        }
    }

    /**
     * The transfer loop sees the flag between frames (a blocked read wakes for
     * it), tells the peer at a frame boundary and closes. Nothing is written
     * from here: a second writer could land inside a frame in progress. This
     * only closes the socket if the loop is still stuck, for example in a write.
     */
    fun cancelTransfer() {
        if (!cancelled.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "dropflow-cancel") {
            runCatching {
                Thread.sleep(Protocol.CANCEL_GRACE_MS)
                // A notice is out: give the peer time to read it before closing.
                if (noticeSent.get()) Thread.sleep(Protocol.NOTICE_DRAIN_MS + 500)
            }
            runCatching { socket.close() }
        }
    }

    /**
     * Tells the peer why this side stops (TRANSFER_CANCEL or ERROR). Call only
     * from the thread that writes the session, between frames. With [drain] it
     * then waits for the peer to read the notice: closing with unread input
     * resets the connection and can destroy it.
     */
    fun sendNotice(tag: FrameTag, payload: ByteArray, drain: Boolean): Boolean {
        val output = frameOutput ?: return false
        if (socket.isClosed || !output.notice(tag, payload)) return false
        noticeSent.set(true)
        if (!drain) return true
        runCatching {
            socket.shutdownOutput()
            val input = socket.getInputStream()
            val sink = ByteArray(16 * 1024)
            val deadline = System.nanoTime() + Protocol.NOTICE_DRAIN_MS * 1_000_000
            while (System.nanoTime() < deadline) {
                try {
                    if (input.read(sink) < 0) break
                } catch (_: SocketTimeoutException) {
                    // Nothing arrived in this slice; keep waiting for the peer to close.
                }
            }
        }
        return true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket for session ${session.sessionId}", e)
        } finally {
            updateState(SessionState.DISCONNECTED)
        }
    }
}
