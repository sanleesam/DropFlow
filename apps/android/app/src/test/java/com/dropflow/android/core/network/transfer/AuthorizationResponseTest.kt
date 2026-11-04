package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AuthorizationResponseTest {
    @Test fun approvalUsesLongWaitThenRestoresStreamingTimeout() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val peer = thread {
                server.accept().use { socket ->
                    Thread.sleep(100)
                    FrameUtils.writeHeader(socket.getOutputStream(), FrameTag.TRANSFER_ACCEPT, 2)
                    socket.getOutputStream().write("OK".toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                socket.soTimeout = 10
                val phases = mutableListOf<Int>()
                val response = readAuthorizationResponse(socket.getInputStream()) { phases += it; socket.soTimeout = it }
                assertEquals(FrameTag.TRANSFER_ACCEPT, response.tag)
                assertEquals(listOf(75_000, 15_000), phases)
                assertEquals(Protocol.READ_TIMEOUT_MS, socket.soTimeout)
            }
            peer.join()
        }
    }

    @Test fun rejectionConsumesItsPayloadAndFailuresRestoreTimeout() {
        val bytes = ByteArrayOutputStream().also {
            FrameUtils.writeHeader(it, FrameTag.TRANSFER_REJECT, 2)
            it.write("NO".toByteArray())
        }.toByteArray()
        val input = ByteArrayInputStream(bytes)
        assertEquals(FrameTag.TRANSFER_REJECT, readAuthorizationResponse(input) {}.tag)
        assertEquals(-1, input.read())
        val phases = mutableListOf<Int>()
        assertThrows(java.io.EOFException::class.java) { readAuthorizationResponse(ByteArrayInputStream(byteArrayOf())) { phases += it } }
        assertEquals(listOf(75_000, 15_000), phases)
    }
}
