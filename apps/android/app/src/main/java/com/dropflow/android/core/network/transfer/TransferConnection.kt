package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

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
    val isCancelled: Boolean get() = cancelled.get()

    var inputStream: InputStream? = null
        private set
    var outputStream: OutputStream? = null
        private set

    private val _currentSession = MutableStateFlow(session)
    val currentSession: StateFlow<TransferSession> = _currentSession.asStateFlow()

    @Volatile
    var transferSessionId: String? = null
        private set

    init {
        try {
            socket.soTimeout = Protocol.READ_TIMEOUT_MS
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()
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
                    val payloadBytes = FrameUtils.readPayload(inp, header.payloadLength)
                    Log.d(TAG, "Received frame ${header.tag} len ${header.payloadLength} on session ${session.sessionId}")

                    when (header.tag) {
                        FrameTag.TRANSFER_REQUEST -> {
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
            val receiver = FileReceiver(context)
            val tracker = TransferProgressTracker(
                sessionId = request.sessionId,
                totalFiles = request.totalFiles,
                totalSizeBytes = request.totalSizeBytes,
                direction = SessionDirection.INCOMING,
                onProgressUpdate = { progress -> onProgressUpdated?.invoke(progress) }
            )
            val error = try {
                FrameUtils.writeTransferAccept(output, TransferAcceptPayload(status = "ACCEPTED"))
                tracker.startSession("Preparing to receive")
                if (receiver.receiveTransferSession(input, output, request, tracker)) null
                else receiver.failureReason ?: "File transfer failed"
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

    fun cancelTransfer() {
        cancelled.set(true)
        // Closing interrupts a blocked read or write. Never interleave a second
        // writer's cancellation frame with a file frame already on this socket.
        runCatching { socket.close() }
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
