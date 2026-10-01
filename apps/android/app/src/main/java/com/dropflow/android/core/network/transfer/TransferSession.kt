package com.dropflow.android.core.network.transfer

import java.util.UUID

enum class SessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CLOSING,
    FAILED
}

enum class SessionDirection {
    INCOMING,
    OUTGOING
}

data class TransferSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val peerUuid: String,
    val peerName: String,
    val peerAddress: String,
    val peerPort: Int,
    val direction: SessionDirection,
    val state: SessionState = SessionState.CONNECTING,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
)
