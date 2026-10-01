package com.dropflow.android.core.network.transfer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

private const val TAG = "DropFlowConnection"

class TransferConnection(
    val socket: Socket,
    val session: TransferSession,
    private val onSessionStateChanged: (TransferSession) -> Unit,
    private val onRequestReceived: ((TransferRequestPayload, (Boolean, String?) -> Unit) -> Unit)? = null,
    private val onProgressUpdated: ((TransferProgressPayload?) -> Unit)? = null,
    private val onTransferFinished: ((TransferRequestPayload, Boolean, String?) -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

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

    fun startListening(context: android.content.Context) {
        scope.launch {
            try {
                val inp = inputStream ?: return@launch
                val out = outputStream ?: return@launch

                while (_currentSession.value.state == SessionState.CONNECTED) {
                    val header = FrameUtils.readHeader(inp)
                    val payloadBytes = FrameUtils.readPayload(inp, header.payloadLength)
                    Log.d(TAG, "Received frame ${header.tag} len ${header.payloadLength} on session ${session.sessionId}")

                    when (header.tag) {
                        FrameTag.TRANSFER_REQUEST -> {
                            val requestPayload = TransferRequestPayload.fromJsonString(String(payloadBytes, StandardCharsets.UTF_8))
                            transferSessionId = requestPayload.sessionId
                            Log.d(TAG, "Parsed TRANSFER_REQUEST from '${requestPayload.senderName}' for file '${requestPayload.primaryFileName}'")
                            val requestFinished = CompletableDeferred<Unit>()

                            val requestHandler = onRequestReceived
                            if (requestHandler == null) {
                                updateState(SessionState.FAILED, "Incoming transfers are not supported")
                                return@launch
                            }
                            requestHandler.invoke(requestPayload) { accepted, reason ->
                                scope.launch {
                                    try {
                                        if (accepted) {
                                            Log.d(TAG, "User accepted transfer for request ${requestPayload.sessionId}. Starting FileReceiver.")
                                            FrameUtils.writeTransferAccept(out, TransferAcceptPayload(status = "ACCEPTED"))

                                            val tracker = TransferProgressTracker(
                                                sessionId = requestPayload.sessionId,
                                                totalFiles = requestPayload.totalFiles,
                                                totalSizeBytes = requestPayload.totalSizeBytes,
                                                direction = SessionDirection.INCOMING,
                                                onProgressUpdate = { progress -> onProgressUpdated?.invoke(progress) }
                                            )

                                            val fileReceiver = FileReceiver(context)
                                            val success = fileReceiver.receiveTransferSession(inp, out, requestPayload, tracker)

                                            onProgressUpdated?.invoke(null)
                                            if (success) {
                                                Log.d(TAG, "FileReceiver session ${requestPayload.sessionId} finished successfully!")
                                                onTransferFinished?.invoke(requestPayload, true, null)
                                                updateState(SessionState.DISCONNECTED)
                                            } else {
                                                val error = "File transfer failed"
                                                onTransferFinished?.invoke(requestPayload, false, error)
                                                updateState(SessionState.FAILED, error)
                                            }
                                        } else {
                                            Log.d(TAG, "User declined transfer for request ${requestPayload.sessionId}: ${reason ?: "Declined"}")
                                            FrameUtils.writeTransferReject(out, TransferRejectPayload(status = "REJECTED", reason = reason ?: "User declined transfer"))
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to execute transfer receiving session", e)
                                        onProgressUpdated?.invoke(null)
                                        onTransferFinished?.invoke(requestPayload, false, e.message)
                                        updateState(SessionState.FAILED, e.message)
                                    } finally {
                                        requestFinished.complete(Unit)
                                    }
                                }
                            }

                            // Only the authorization callback may read this stream after a request.
                            // Waiting here prevents a second reader from consuming FILE_HEADER frames.
                            requestFinished.await()
                            return@launch
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

    fun cancelTransfer() {
        scope.launch {
            try {
                outputStream?.let { FrameUtils.writeTransferCancel(it, transferSessionId ?: session.sessionId) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to notify peer about cancellation", e)
            } finally {
                close()
            }
        }
    }

    fun close() {
        try {
            updateState(SessionState.CLOSING)
            inputStream?.close()
            outputStream?.close()
            if (!socket.isClosed) {
                socket.close()
            }
            updateState(SessionState.DISCONNECTED)
            Log.d(TAG, "Socket cleanly closed for session ${session.sessionId}")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket for session ${session.sessionId}", e)
        } finally {
            scope.cancel()
        }
    }
}
