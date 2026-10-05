package com.dropflow.android.core.network.transfer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "DropFlowTcpServer"

enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

class TcpTransferServer(
    private val context: Context,
    private val onRequestReceived: (TransferRequestPayload, (Boolean, String?) -> Unit) -> Unit,
    private val onIncomingConnection: (TransferConnection) -> Unit,
    private val onProgressUpdated: (TransferProgressPayload?) -> Unit,
    private val onTransferFinished: (TransferRequestPayload, Boolean, String?) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var serverSocket: ServerSocket? = null
    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val activeConnectionsMap = ConcurrentHashMap<String, TransferConnection>()

    suspend fun start(port: Int = Protocol.DEFAULT_PORT) = withContext(Dispatchers.IO) {
        if (_status.value == ServerStatus.RUNNING) return@withContext

        _status.value = ServerStatus.STARTING
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            _status.value = ServerStatus.RUNNING
            Log.d(TAG, "TCP Server started and listening on port $port")

            scope.launch {
                acceptLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TCP Server on port $port", e)
            _status.value = ServerStatus.ERROR
        }
    }

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        val server = serverSocket ?: return@withContext
        while (_status.value == ServerStatus.RUNNING && !server.isClosed) {
            try {
                val clientSocket: Socket = server.accept()
                val peerAddr = clientSocket.inetAddress?.hostAddress ?: "unknown"
                val peerPort = clientSocket.port
                Log.d(TAG, "Incoming TCP connection accepted from $peerAddr:$peerPort")

                val session = TransferSession(
                    peerUuid = "peer-$peerAddr",
                    peerName = "Peer ($peerAddr)",
                    peerAddress = peerAddr,
                    peerPort = peerPort,
                    direction = SessionDirection.INCOMING,
                    state = SessionState.CONNECTING
                )

                val connection = TransferConnection(
                    socket = clientSocket,
                    session = session,
                    onSessionStateChanged = { updatedSession ->
                        if (updatedSession.state == SessionState.DISCONNECTED || updatedSession.state == SessionState.FAILED) {
                            activeConnectionsMap.remove(updatedSession.sessionId)
                        }
                    },
                    onRequestReceived = onRequestReceived,
                    onProgressUpdated = onProgressUpdated,
                    onTransferFinished = onTransferFinished
                )

                activeConnectionsMap[session.sessionId] = connection
                onIncomingConnection(connection)

                scope.launch {
                    val handshakeSuccess = connection.performIncomingHandshake()
                    if (handshakeSuccess) {
                        connection.startListening(context)
                    } else {
                        connection.close()
                    }
                }
            } catch (e: Exception) {
                if (_status.value == ServerStatus.RUNNING) {
                    Log.w(TAG, "Accept loop exception: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping TCP Server...")
        _status.value = ServerStatus.STOPPED
        try {
            activeConnectionsMap.values.forEach { it.close() }
            activeConnectionsMap.clear()
            serverSocket?.close()
            serverSocket = null
            Log.d(TAG, "TCP Server stopped cleanly")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TCP Server", e)
        } finally {
            scope.cancel()
        }
    }
}
