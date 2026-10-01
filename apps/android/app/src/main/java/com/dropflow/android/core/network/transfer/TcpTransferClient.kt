package com.dropflow.android.core.network.transfer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

private const val TAG = "DropFlowTcpClient"

class TcpTransferClient {

    suspend fun connect(
        context: Context,
        peerUuid: String,
        peerName: String,
        host: String,
        port: Int = Protocol.DEFAULT_PORT,
        sessionId: String = UUID.randomUUID().toString(),
        onSessionStateChanged: (TransferSession) -> Unit
    ): TransferConnection? = withContext(Dispatchers.IO) {
        val initialSession = TransferSession(
            sessionId = sessionId,
            peerUuid = peerUuid,
            peerName = peerName,
            peerAddress = host,
            peerPort = port,
            direction = SessionDirection.OUTGOING,
            state = SessionState.CONNECTING
        )

        Log.d(TAG, "Initiating outgoing TCP connection to $host:$port (peerUuid: $peerUuid)")
        return@withContext try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), Protocol.CONNECT_TIMEOUT_MS)

            val connection = TransferConnection(socket, initialSession, onSessionStateChanged)
            val success = connection.performOutgoingHandshake()

            if (success) {
                Log.d(TAG, "Outgoing connection and handshake successful to $host:$port")
                // Do NOT call startListening here — outgoing connections are driven by
                // FileSender directly. Calling startListening would block reading an
                // incoming frame, preventing us from ever writing TRANSFER_REQUEST.
                connection
            } else {
                Log.e(TAG, "Outgoing handshake failed to $host:$port")
                connection.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to TCP peer at $host:$port: ${e.message}", e)
            initialSession.copy(state = SessionState.FAILED, errorMessage = e.message).also {
                onSessionStateChanged(it)
            }
            null
        }
    }
}
