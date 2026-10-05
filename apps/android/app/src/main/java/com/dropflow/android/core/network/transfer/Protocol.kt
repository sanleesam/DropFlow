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

    const val MAX_METADATA_SIZE = 64 * 1024 // 64 KB
    const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
    const val MAX_CHUNK_SIZE = 1024 * 1024 // 1 MB
}
