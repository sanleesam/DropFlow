package com.dropflow.android.core.network.transfer

import java.nio.charset.StandardCharsets

object Protocol {
    val MAGIC_BYTES = byteArrayOf(0x44, 0x46, 0x50, 0x31) // "DFP1"
    const val PROTOCOL_VERSION: Byte = 1
    const val PROTOCOL_STRING = "DFP/1"

    const val HELLO_HANDSHAKE = "HELLO DFP/1"
    const val HELLO_ACK = "HELLO_ACK DFP/1"

    const val DEFAULT_PORT = 42100
    const val HEADER_SIZE = 10 // [4 magic][1 ver][1 tag][4 len]

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    // The receiving app gives the user 60 seconds to decide.
    const val APPROVAL_TIMEOUT_MS = 75_000

    const val MAX_METADATA_SIZE = 64 * 1024 // 64 KB
    // DFP/1.1 large manifests and resume lists.
    const val MAX_LARGE_METADATA_SIZE = 16 * 1024 * 1024
    const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
    const val MAX_CHUNK_SIZE = 1024 * 1024 // 1 MB

    // DFP/1.1 features, used only when both peers ask for them.
    const val FEATURE_BATCH = "batch"
    const val FEATURE_RESUME = "resume"
    // A request is read before anyone approves it, so its fields are bounded.
    const val MAX_FEATURES = 16
    const val MAX_FEATURE_NAME_BYTES = 32

    const val HEARTBEAT_INTERVAL_MS = 2_000L
    const val PEER_CHECK_INTERVAL_MS = 100L
    // How long a peer keeps reading after it sent a cancel or error notice.
    const val NOTICE_DRAIN_MS = 1_500L
    // A cancelled transfer that is stuck in a write is closed after this long.
    const val CANCEL_GRACE_MS = 1_500L
    // A peer without large manifests drops the connection well within this.
    const val LARGE_REQUEST_REJECT_WINDOW_MS = 5_000L
}
