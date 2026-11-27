package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The direct link against the other implementation: the desktop's command-line
 * peer (Rust, the same session code as the desktop app) hosts a "private
 * network" that is this machine's loopback, and this engine joins it. Proves
 * the two link phases agree on the wire, without any radio.
 *
 * Runs only where the peer is built (`cargo build --release --example dfp_peer`
 * in apps/desktop/src-tauri). Set the system property or environment variable
 * `DFP_PEER` to use a binary elsewhere.
 */
class RustPeerLinkTest {
    @get:Rule val folder = TemporaryFolder()

    private var peer: Process? = null
    private val started = CopyOnWriteArrayList<Process>()
    private val peerOutput = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<Socket>()

    private val files: List<TestFile> = listOf(
        "video.bin" to randomBytes(3 * 1024 * 1024 + 17, 1),
        "photo.jpg" to randomBytes(300_000, 2),
        "notes.txt" to "moved over the direct link".toByteArray(),
        "empty.dat" to ByteArray(0)
    )

    @Before fun requirePeer() {
        assumeTrue("the desktop command-line peer is not built; skipping", peerBinary() != null)
    }

    @After fun stopPeer() {
        // Whatever happened, no child process and no open socket outlives the test.
        started.forEach {
            it.destroyForcibly()
            it.waitFor(10, TimeUnit.SECONDS)
        }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun peerBinary(): File? {
        val named = System.getProperty("DFP_PEER") ?: System.getenv("DFP_PEER")
        if (!named.isNullOrBlank()) return File(named).takeIf { it.canExecute() }
        // The tests run from apps/android/app; the desktop app is a sibling of apps/android.
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val examples = File(directory ?: return null, "apps/desktop/src-tauri/target/release/examples")
            listOf("dfp_peer", "dfp_peer.exe").map { File(examples, it) }.firstOrNull { it.canExecute() }?.let { return it }
            directory = directory?.parentFile
        }
        return null
    }

    private fun startPeer(vararg arguments: String): Process {
        val process = ProcessBuilder(listOf(checkNotNull(peerBinary()).path) + arguments)
            .redirectErrorStream(true)
            .start()
        peer = process
        started += process
        inBackground { process.inputStream.bufferedReader().forEachLine { peerOutput += it } }
        return process
    }

    /** The first line of the peer's output that contains [text]. Fails instead of waiting forever. */
    private fun peerLine(text: String, seconds: Long = 30): String {
        val giveUp = System.nanoTime() + seconds * 1_000_000_000
        while (true) {
            peerOutput.firstOrNull { text in it }?.let { return it }
            if (System.nanoTime() >= giveUp || (peer?.isAlive == false && peerOutput.none { text in it })) {
                // One more look: the reader thread may still be delivering the last lines.
                Thread.sleep(200)
                peerOutput.firstOrNull { text in it }?.let { return it }
                fail("the peer never printed '$text'. Its output:\n" + peerOutput.joinToString("\n"))
            }
            Thread.sleep(20)
        }
    }

    private fun awaitPeerExit() {
        val process = checkNotNull(peer)
        assertTrue("the peer did not exit. Its output:\n" + peerOutput.joinToString("\n"), process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(peerOutput.joinToString("\n"), 0, process.exitValue())
    }

    /** This phone's part: it joins the network it is offered and listens on it. Here that is loopback. */
    private class Joiner : LoopbackLink(0, true) {
        val offers = CopyOnWriteArrayList<DirectOffer>()
        override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
            calls += "join"
            offers += offer
            tick()
            return JoinedLink("127.0.0.1", listen())
        }
    }

    private fun assertOfferFromAHostThatConnects(link: Joiner) {
        val offer = link.offers.single()
        assertTrue(offer.ssid.isNotEmpty() && offer.passphrase.isNotEmpty())
        assertTrue("token '${offer.token}'", Regex("[0-9a-f]{32}").matches(offer.token))
        // The desktop never listens: the joiner does, and says where.
        assertNull(offer.hostPort)
    }

    private class Sent(val error: String?, val linked: List<Socket>, val progress: List<TransferProgressPayload>)

    /**
     * Sends [files] to the peer the way TransferEngine does: TcpTransferClient
     * connects and shakes hands, FileSender runs the session, and the
     * connection adopts the link's socket.
     */
    private fun sendToPeer(port: Int, link: DirectLink?): Sent {
        val request = requestFor(files, BATCH_AND_RESUME)
        val linked = CopyOnWriteArrayList<Socket>()
        val progress = CopyOnWriteArrayList<TransferProgressPayload>()
        val error = inBackground {
            runBlocking {
                val connection = checkNotNull(
                    TcpTransferClient().connect("dfp-peer", "DropFlow test peer", "127.0.0.1", port, request.sessionId) {}
                ) { "could not connect to the peer" }
                sockets += connection.socket
                try {
                    FileSender().sendTransferSession(
                        socketInputStream = checkNotNull(connection.inputStream),
                        socketOutputStream = checkNotNull(connection.outputStream),
                        requestPayload = request,
                        openFile = { ByteArrayInputStream(files[it].second) },
                        progressTracker = TransferProgressTracker(request.sessionId, request.totalFiles, request.totalSizeBytes,
                            SessionDirection.OUTGOING) { progress += it },
                        setReadTimeout = { connection.readTimeoutMs = it },
                        isCancelled = { connection.isCancelled },
                        sendNotice = { tag, payload -> connection.sendNotice(tag, payload, drain = true) },
                        direct = link?.let {
                            DirectOptions(it, adopt = { socket ->
                                linked += socket
                                sockets += socket
                                connection.adopt(socket)
                            })
                        }
                    )
                } finally {
                    connection.close()
                }
            }
        }.result(60)
        return Sent(error, linked, progress)
    }

    private class Received(val ok: Boolean, val failure: String?, val request: TransferRequestPayload, val storage: FakeStorage,
                           val linked: List<Socket>, val progress: List<TransferProgressPayload>)

    /**
     * Receives one transfer from the peer the way the app does: the incoming
     * handshake, the request read and validated before anything is accepted,
     * then FileReceiver, with the connection adopting the link's socket.
     * Only the storage is a temporary folder instead of the phone's.
     */
    private fun receiveFromPeer(server: ServerSocket, link: DirectLink?): Received {
        val storage = FakeStorage(folder.newFolder())
        val linked = CopyOnWriteArrayList<Socket>()
        val progress = CopyOnWriteArrayList<TransferProgressPayload>()
        return inBackground {
            server.soTimeout = 30_000
            val socket = server.accept()
            sockets += socket
            val session = TransferSession(peerUuid = "peer", peerName = "Peer", peerAddress = "127.0.0.1", peerPort = socket.port,
                direction = SessionDirection.INCOMING)
            val connection = TransferConnection(socket, session, onSessionStateChanged = {})
            try {
                runBlocking {
                    assertTrue("incoming handshake", connection.performIncomingHandshake())
                    val input = checkNotNull(connection.inputStream)
                    val header = FrameUtils.readHeader(input)
                    assertTrue("${header.tag}", header.tag == FrameTag.TRANSFER_REQUEST || header.tag == FrameTag.TRANSFER_REQUEST_LARGE)
                    val request = TransferRequestPayload.fromJsonString(String(FrameUtils.readPayload(input, header.payloadLength)))
                    ReceiveValidation.request(request)
                    val receiver = FileReceiver(storage)
                    val ok = receiver.receiveTransferSession(
                        input, checkNotNull(connection.outputStream), request,
                        TransferProgressTracker(request.sessionId, request.totalFiles, request.totalSizeBytes,
                            SessionDirection.INCOMING) { progress += it },
                        isCancelled = { connection.isCancelled },
                        sendNotice = { tag, payload -> connection.sendNotice(tag, payload, drain = true) },
                        direct = link?.let {
                            DirectOptions(it, adopt = { direct ->
                                linked += direct
                                sockets += direct
                                connection.adopt(direct)
                            })
                        }
                    )
                    Received(ok, receiver.failureReason, request, storage, linked, progress)
                }
            } finally {
                connection.close()
            }
        }.result(60)
    }

    /** Writes [files] where the peer can read them and returns their paths in order. */
    private fun sourceFiles(): List<String> {
        val directory = folder.newFolder()
        return files.map { (name, bytes) -> File(directory, name).apply { writeBytes(bytes) }.path }
    }

    private fun assertPeerSaved(directory: File) {
        for ((name, bytes) in files) {
            val saved = File(directory, name)
            assertTrue("$name was not saved by the peer: ${directory.list()?.toList()}", saved.isFile)
            assertArrayEquals(name, bytes, saved.readBytes())
        }
    }

    private fun assertSaved(storage: FakeStorage) {
        val saved = storage.publishedFiles()
        assertEquals(files.map { it.first }.toSet(), saved.keys)
        files.forEach { (name, bytes) -> assertArrayEquals(name, bytes, saved[name]) }
        assertEquals(files.size, storage.published.list()!!.size)
    }

    private fun listeningPort(): Int = peerLine("listening on port").substringAfterLast(' ').trim().toInt()

    // ── This phone sends and joins; the computer receives and hosts ─────────

    @Test fun aKotlinSenderJoinsTheLinkARustReceiverHosts() {
        val saved = folder.newFolder()
        startPeer("receive", saved.path, "--count", "1", "--link", "host")
        val link = Joiner()

        val sent = sendToPeer(listeningPort(), link)

        assertNull("the sender failed. Peer output:\n" + peerOutput.joinToString("\n"), sent.error)
        awaitPeerExit()
        // Both sides say the data went over the link.
        assertTrue(peerLine("[Perf] receive").contains("direct=true"))
        assertTrue(peerLine("[Perf] receive").contains("files=${files.size} bytes=${files.sumOf { it.second.size }} "))
        assertEquals(1, sent.linked.size)
        assertTrue(sent.linked.single().inetAddress.isLoopbackAddress)
        assertTrue(sent.progress.any { it.phase == TransferPhase.LINKING })
        assertTrue(sent.progress.filter { it.phase == TransferPhase.STREAMING }.let { it.isNotEmpty() && it.all { each -> each.direct } })
        assertOfferFromAHostThatConnects(link)
        assertEquals("close", link.calls.last())
        assertFalse("connect" in link.calls)
        assertPeerSaved(saved)
    }

    // ── This phone receives and joins; the computer sends and hosts ─────────

    @Test fun aKotlinReceiverJoinsTheLinkARustSenderHosts() {
        val link = Joiner()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            startPeer("send", "127.0.0.1", server.localPort.toString(), "--link", "host", *sourceFiles().toTypedArray())

            val received = receiveFromPeer(server, link)

            assertTrue("the receiver failed: ${received.failure}. Peer output:\n" + peerOutput.joinToString("\n"), received.ok)
            // The computer offered to host and nothing else.
            assertTrue(Protocol.FEATURE_DIRECT in received.request.features)
            assertEquals(DirectCaps(hostRank = 2, join = false), received.request.direct)
            awaitPeerExit()
            // This phone grants batch and the link, not resume.
            assertEquals("accepted batch=true resume=false", peerLine("accepted "))
            assertTrue(peerLine("[Perf] send").contains("direct=true"))
            assertEquals(1, received.linked.size)
            assertTrue(received.progress.any { it.phase == TransferPhase.LINKING })
            assertTrue(received.progress.filter { it.phase == TransferPhase.STREAMING }.let { it.isNotEmpty() && it.all { each -> each.direct } })
            assertOfferFromAHostThatConnects(link)
            assertEquals("close", link.calls.last())
            assertSaved(received.storage)
        }
    }

    // ── The same, with nothing offered by this phone ────────────────────────

    @Test fun withoutALinkOnThisSideBothDirectionsStayOnTheSharedConnection() {
        // Sending: the computer could host, but this phone asked for nothing.
        val saved = folder.newFolder()
        startPeer("receive", saved.path, "--count", "1", "--link", "host")
        val sent = sendToPeer(listeningPort(), link = null)
        assertNull("the sender failed. Peer output:\n" + peerOutput.joinToString("\n"), sent.error)
        awaitPeerExit()
        assertTrue(peerLine("[Perf] receive").contains("direct=false"))
        assertTrue(sent.linked.isEmpty())
        assertFalse(sent.progress.any { it.direct || it.phase == TransferPhase.LINKING })
        assertPeerSaved(saved)

        // Receiving: the computer offers to host; this phone grants batch only.
        peerOutput.clear()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            startPeer("send", "127.0.0.1", server.localPort.toString(), "--link", "host", *sourceFiles().toTypedArray())
            val received = receiveFromPeer(server, link = null)
            assertTrue("the receiver failed: ${received.failure}. Peer output:\n" + peerOutput.joinToString("\n"), received.ok)
            assertTrue(Protocol.FEATURE_DIRECT in received.request.features)
            awaitPeerExit()
            assertTrue(peerLine("[Perf] send").contains("direct=false"))
            assertTrue(received.linked.isEmpty())
            assertFalse(received.progress.any { it.direct || it.phase == TransferPhase.LINKING })
            assertSaved(received.storage)
        }
    }

    // ── The host's part, which no phone plays yet ───────────────────────────

    @Test fun aKotlinHostAndARustJoinerAgreeWhicheverSideListens() {
        for (hostListens in listOf(false, true)) {
            // Sending and hosting; the computer receives and joins.
            peerOutput.clear()
            val saved = folder.newFolder()
            startPeer("receive", saved.path, "--count", "1", "--link", "join")
            val sending = LoopbackLink(2, false).apply { this.hostListens = hostListens }
            val sent = sendToPeer(listeningPort(), sending)
            assertNull("hostListens=$hostListens: the sender failed. Peer output:\n" + peerOutput.joinToString("\n"), sent.error)
            awaitPeerExit()
            assertTrue(peerLine("[Perf] receive").contains("direct=true"))
            assertEquals(1, sent.linked.size)
            assertEquals(!hostListens, "connect" in sending.calls)
            assertPeerSaved(saved)

            // Receiving and hosting; the computer sends and joins.
            peerOutput.clear()
            val receiving = LoopbackLink(2, false).apply { this.hostListens = hostListens }
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                startPeer("send", "127.0.0.1", server.localPort.toString(), "--link", "join", *sourceFiles().toTypedArray())
                val received = receiveFromPeer(server, receiving)
                assertTrue("hostListens=$hostListens: ${received.failure}. Peer output:\n" + peerOutput.joinToString("\n"), received.ok)
                assertEquals(DirectCaps(hostRank = 0, join = true), received.request.direct)
                awaitPeerExit()
                assertTrue(peerLine("[Perf] send").contains("direct=true"))
                assertEquals(1, received.linked.size)
                assertEquals(!hostListens, "connect" in receiving.calls)
                assertSaved(received.storage)
            }
        }
    }
}
