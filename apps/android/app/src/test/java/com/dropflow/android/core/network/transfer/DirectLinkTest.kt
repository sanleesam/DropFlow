package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The direct link (protocol section 7.5) with a loopback "network": the
 * negotiation in both roles, the move to the new connection, and every way
 * back to the shared one. Nothing here touches a radio.
 */
class DirectLinkTest {
    @get:Rule val folder = TemporaryFolder()

    /** The real limits are 25, 75 and 30 seconds; tests cannot wait that long. */
    private val fast = LinkLimits(offerMs = 4_000, joinMs = 5_000, connectMs = 4_000, greetingMs = 1_500, pollMs = 20)

    private val files: List<TestFile> = listOf(
        "a.bin" to randomBytes(1_200_000, 1),
        "b.bin" to randomBytes(300_000, 2),
        "c.txt" to "c".toByteArray(),
        "empty" to ByteArray(0)
    )
    private val small: List<TestFile> = listOf("note.txt" to randomBytes(2_000, 3))

    private val token = "0123456789abcdef0123456789abcdef"
    private val grantBoth = """{"status":"ACCEPTED","features":["batch","direct"],"directHost":"receiver"}"""
    private val withDirect = BATCH_AND_RESUME + Protocol.FEATURE_DIRECT

    private fun offer(hostPort: Int? = null) =
        DirectOffer(LoopbackLink.SSID, LoopbackLink.PASSPHRASE, token, hostPort).toJsonObject().toString()

    private fun JSONObject.features(): List<String>? =
        optJSONArray("features")?.let { list -> (0 until list.length()).map { list.getString(it) } }

    private fun options(link: DirectLink, limits: LinkLimits = fast) = DirectOptions(link, limits = limits)

    /** A request as a computer that can host would send it. */
    private fun hostingRequest(files: List<TestFile>) =
        requestFor(files, withDirect).copy(direct = DirectCaps(hostRank = 2, join = false))

    private fun published(files: List<TestFile>) = files.associate { it.first.substringAfterLast('/') to it.second }

    private fun assertSameFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual[name]) }
    }

    private class Receive(val receiver: FileReceiver, val done: Future<Boolean>)

    private fun startReceiver(
        socket: Socket,
        request: TransferRequestPayload,
        storage: FakeStorage,
        direct: DirectOptions?,
        input: InputStream = socket.getInputStream(),
        isCancelled: () -> Boolean = { false }
    ): Receive {
        socket.soTimeout = 15_000
        val receiver = FileReceiver(storage)
        return Receive(receiver, inBackground {
            runBlocking {
                receiver.receiveTransferSession(input, socket.getOutputStream(), request,
                    trackerFor(request, SessionDirection.INCOMING), isCancelled, direct = direct)
            }
        })
    }

    /** The host's side of the greeting when the joiner listens: connect, handshake, show the token. */
    private fun connectAsHost(joined: JSONObject, shown: String = token): Peer {
        val peer = Peer(Socket(joined.getString("address"), joined.getInt("port")))
        peer.write(FrameTag.HEARTBEAT, Protocol.HELLO_HANDSHAKE)
        assertEquals(Protocol.HELLO_ACK, String(peer.expect(FrameTag.HEARTBEAT)))
        peer.write(FrameTag.DIRECT_RESULT, shown)
        return peer
    }

    /** The host's side of the greeting when it listens itself. */
    private fun acceptAsHost(server: ServerSocket): Peer {
        server.soTimeout = 10_000
        val peer = Peer(server.accept())
        assertEquals(Protocol.HELLO_HANDSHAKE, String(peer.expect(FrameTag.HEARTBEAT)))
        peer.write(FrameTag.HEARTBEAT, Protocol.HELLO_ACK)
        assertEquals(token, String(peer.expect(FrameTag.DIRECT_RESULT)))
        peer.write(FrameTag.DIRECT_RESULT, "OK")
        return peer
    }

    private fun failure(peer: Peer): String = String(peer.await(FrameTag.DIRECT_RESULT))

    /** What was asked of a link, in order. Giving it back twice is the same as once. */
    private fun steps(link: LoopbackLink): List<String> =
        link.calls.fold(emptyList()) { seen, call -> if (call == "close" && seen.lastOrNull() == "close") seen else seen + call }

    // ── The wire format ─────────────────────────────────────────────────────

    @Test fun theThreeLinkFramesHaveTheSpecifiedCodesAndTheControlLimit() {
        assertEquals(0x0E.toByte(), FrameTag.DIRECT_OFFER.code)
        assertEquals(0x0F.toByte(), FrameTag.DIRECT_JOINED.code)
        assertEquals(0x10.toByte(), FrameTag.DIRECT_RESULT.code)
        for (tag in listOf(FrameTag.DIRECT_OFFER, FrameTag.DIRECT_JOINED, FrameTag.DIRECT_RESULT)) {
            assertEquals(tag, FrameTag.fromCode(tag.code))
            assertEquals(64 * 1024, FrameUtils.maxPayloadSize(tag))
            val header = ByteArray(Protocol.HEADER_SIZE)
            FrameUtils.encodeHeader(header, tag, 64 * 1024)
            assertEquals(64 * 1024, FrameUtils.readHeader(ByteArrayInputStream(header)).payloadLength)
            assertThrows(IllegalArgumentException::class.java) { FrameUtils.encodeHeader(header, tag, 64 * 1024 + 1) }
        }
        val wire = ByteArrayOutputStream()
        FrameUtils.writeFrame(wire, FrameTag.DIRECT_RESULT, "OK".toByteArray())
        assertArrayEquals(byteArrayOf(0x44, 0x46, 0x50, 0x31, 1, 0x10, 0, 0, 0, 2) + "OK".toByteArray(), wire.toByteArray())
    }

    @Test fun theRequestCarriesDirectOnlyWhenOfferedAndReadsItLeniently() {
        // Without it the request is byte for byte what it was.
        val plain = requestFor(small, BATCH_AND_RESUME)
        assertFalse(plain.toJsonObject().has("direct"))
        assertNull(TransferRequestPayload.fromJsonString(plain.toJsonObject().toString()).direct)

        val offering = plain.copy(features = withDirect, direct = DirectCaps(hostRank = 0, join = true)).toJsonObject()
        assertEquals(setOf("hostRank", "join"), offering.getJSONObject("direct").keySet())
        assertEquals(0, offering.getJSONObject("direct").getInt("hostRank"))
        assertTrue(offering.getJSONObject("direct").getBoolean("join"))
        assertEquals(DirectCaps(0, true), TransferRequestPayload.fromJsonString(offering.toString()).direct)

        fun parsed(direct: Any?) =
            TransferRequestPayload.fromJsonString(plain.toJsonObject().put("direct", direct).toString()).direct
        // What a computer sends.
        assertEquals(DirectCaps(2, false), parsed(JSONObject("""{"hostRank":2,"join":false}""")))
        // Missing or odd members mean "cannot".
        assertEquals(DirectCaps(0, false), parsed(JSONObject()))
        assertEquals(DirectCaps(0, false), parsed(JSONObject("""{"hostRank":"2","join":"yes"}""")))
        assertEquals(DirectCaps(0, true), parsed(JSONObject("""{"hostRank":-4,"join":true,"future":1}""")))
        assertEquals(DirectCaps(255, false), parsed(JSONObject("""{"hostRank":100000}""")))
        // Anything that is not an object offers nothing, and never fails the request.
        for (odd in listOf<Any>("yes", 7, true, org.json.JSONArray(listOf(1)))) assertNull(parsed(odd))
    }

    @Test fun theAcceptNamesTheHostOnlyWhenDirectIsGranted() {
        val plain = TransferAcceptPayload(status = "ACCEPTED", features = BATCH).toJsonObject()
        assertEquals(setOf("status", "features"), plain.keySet())
        // The original reply never grows a field.
        assertEquals(setOf("status", "resumeOffset"), TransferAcceptPayload(directHost = "sender").toJsonObject().keySet())

        val granting = TransferAcceptPayload(features = BATCH + Protocol.FEATURE_DIRECT, directHost = "sender").toJsonObject()
        assertEquals(setOf("status", "features", "directHost"), granting.keySet())
        assertEquals("sender", granting.getString("directHost"))
        assertEquals("sender", TransferAcceptPayload.directHost(granting.toString().toByteArray()))
        assertEquals("sender", TransferAcceptPayload.fromJsonString(granting.toString()).directHost)

        fun host(text: String) = TransferAcceptPayload.directHost(text.toByteArray())
        assertEquals("receiver", host(grantBoth))
        for (none in listOf("ACCEPTED", "", OLD_ACCEPT, """{"status":"ACCEPTED","directHost":7}""", """{"directHost":null}""")) {
            assertNull(none, host(none))
        }
    }

    @Test fun offerAndJoinedUseTheSpecifiedNames() {
        val listening = DirectOffer("DIRECT-DF-1", "passphrase", token, hostPort = 42_200)
        assertEquals(setOf("ssid", "passphrase", "token", "hostPort"), listening.toJsonObject().keySet())
        assertEquals(listening, DirectOffer.parse(listening.toJsonObject().toString().toByteArray()))
        // A Windows host does not listen: no hostPort at all.
        val connecting = listening.copy(hostPort = null)
        assertEquals(setOf("ssid", "passphrase", "token"), connecting.toJsonObject().keySet())
        assertEquals(connecting, DirectOffer.parse("""{"ssid":"DIRECT-DF-1","passphrase":"passphrase","token":"$token","extra":1}""".toByteArray()))
        assertEquals(connecting, DirectOffer.parse("""{"ssid":"DIRECT-DF-1","passphrase":"passphrase","token":"$token","hostPort":null}""".toByteArray()))
        for (bad in listOf(
            "", "FAILED", "[]", """{"ssid":"x","passphrase":"y"}""", """{"ssid":"","passphrase":"y","token":"t"}""",
            """{"ssid":"x","passphrase":"y","token":""}""", """{"ssid":7,"passphrase":"y","token":"t"}""",
            """{"ssid":"x","passphrase":"y","token":"t","hostPort":0}""", """{"ssid":"x","passphrase":"y","token":"t","hostPort":70000}""",
            """{"ssid":"x","passphrase":"y","token":"t","hostPort":"80"}"""
        )) assertNull(bad, DirectOffer.parse(bad.toByteArray()))

        val joined = DirectJoined("192.168.137.24", 50_123)
        assertEquals(setOf("address", "port"), joined.toJsonObject().keySet())
        assertEquals(joined, DirectJoined.parse("""{"address":"192.168.137.24","port":50123}""".toByteArray()))
        for (bad in listOf("", "{}", """{"address":"","port":1}""", """{"address":"a","port":0}""", """{"address":"a","port":65536}""", """{"address":"a"}""")) {
            assertNull(bad, DirectJoined.parse(bad.toByteArray()))
        }
    }

    @Test fun theHigherRankHostsAndTheOtherMustBeAbleToJoin() {
        fun host(sender: Pair<Int, Boolean>, receiver: Pair<Int, Boolean>) =
            chooseHost(DirectCaps(sender.first, sender.second), DirectCaps(receiver.first, receiver.second))
        // This phone (rank 0, joins) with a computer (rank 2): the computer hosts, whoever sends.
        assertEquals("receiver", host(0 to true, 2 to false))
        assertEquals("sender", host(2 to false, 0 to true))
        // Both could host: the higher rank does; on a tie the receiver.
        assertEquals("receiver", host(1 to true, 2 to true))
        assertEquals("sender", host(2 to true, 1 to true))
        assertEquals("receiver", host(2 to true, 2 to true))
        // The higher rank cannot be joined by the other: the lower one hosts.
        assertEquals("receiver", host(2 to true, 1 to false))
        // No workable pairing: two joiners, two hosts that cannot join, nothing at all.
        assertNull(host(0 to true, 0 to true))
        assertNull(host(2 to false, 2 to false))
        assertNull(host(0 to false, 2 to false))
        assertNull(host(0 to false, 0 to false))
    }

    @Test fun theSpecifiedWaitsAreTheDefaults() {
        assertEquals(LinkLimits(offerMs = 25_000, joinMs = 75_000, connectMs = 30_000, greetingMs = 5_000, pollMs = 250), LinkLimits())
    }

    @Test fun thisPhoneJoinsOnlyWithTheSettingAndroid12AndASecondWifiConnection() {
        assertEquals(DirectCaps(hostRank = 0, join = true), DirectLinkSupport.caps(enabled = true, sdk = 31, keepsWifi = true))
        assertEquals(DirectCaps(0, true), DirectLinkSupport.caps(enabled = true, sdk = 34, keepsWifi = true))
        // Each condition alone is not enough, and the phone never hosts.
        assertEquals(DirectCaps(0, false), DirectLinkSupport.caps(enabled = false, sdk = 34, keepsWifi = true))
        assertEquals(DirectCaps(0, false), DirectLinkSupport.caps(enabled = true, sdk = 30, keepsWifi = true))
        assertEquals(DirectCaps(0, false), DirectLinkSupport.caps(enabled = true, sdk = 34, keepsWifi = false))
    }

    @Test fun theHostOfAJoinedNetworkIsItsOwnPrivateOnLinkAddress() {
        fun ip(text: String) = InetAddress.getByName(text)
        val mine = listOf(ip("192.168.137.24"), ip("fe80::1"))
        val onLink = { address: InetAddress -> address.hostAddress!!.startsWith("192.168.137.") }
        assertEquals(ip("192.168.137.1"), pickLinkHost(listOf(ip("192.168.137.1")), mine, onLink))
        // Not this phone, not a public or off-link address, not IPv6; the next candidate is used.
        assertEquals(ip("192.168.137.1"), pickLinkHost(
            listOf(ip("192.168.137.24"), ip("8.8.8.8"), ip("10.0.0.1"), ip("fe80::2"), ip("192.168.137.1")), mine, onLink))
        assertNull(pickLinkHost(listOf(ip("192.168.137.24"), ip("8.8.8.8"), ip("10.0.0.1")), mine, onLink))
        assertNull(pickLinkHost(emptyList(), mine, onLink))
    }

    // ── Both engines against each other, in every arrangement ───────────────

    /** Fails after [limit] bytes: a shared network that cannot carry the files. */
    private class Capped(private val raw: OutputStream, private val limit: Long) : OutputStream() {
        val written = AtomicLong()
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (written.addAndGet(length.toLong()) > limit) throw IOException("the shared network carried more than $limit bytes")
            raw.write(buffer, offset, length)
        }
        override fun flush() = raw.flush()
    }

    private class Outcome(
        val sent: String?,
        val received: Boolean,
        val failure: String?,
        val storage: FakeStorage,
        val sharedBytes: Long,
        val senderProgress: List<TransferProgressPayload>,
        val receiverProgress: List<TransferProgressPayload>
    ) {
        val direct get() = senderProgress.any { it.direct } || receiverProgress.any { it.direct }
    }

    /** FileSender and FileReceiver over one loopback connection, the request read from the wire as in the app. */
    private fun transfer(
        files: List<TestFile>,
        senderLink: DirectLink?,
        receiverLink: DirectLink?,
        features: List<String> = BATCH_AND_RESUME,
        sharedLimit: Long = Long.MAX_VALUE,
        senderLimits: LinkLimits = fast,
        receiverLimits: LinkLimits = fast
    ): Outcome {
        val storage = FakeStorage(folder.newFolder())
        val (near, far) = socketPair()
        near.soTimeout = 15_000
        far.soTimeout = 15_000
        val shared = Capped(near.getOutputStream(), sharedLimit)
        val senderProgress = CopyOnWriteArrayList<TransferProgressPayload>()
        val receiverProgress = CopyOnWriteArrayList<TransferProgressPayload>()
        val receiving = inBackground {
            val header = FrameUtils.readHeader(far.getInputStream())
            val request = TransferRequestPayload.fromJsonString(String(FrameUtils.readPayload(far.getInputStream(), header.payloadLength)))
            val receiver = FileReceiver(storage)
            val tracker = TransferProgressTracker(request.sessionId, request.totalFiles, request.totalSizeBytes,
                SessionDirection.INCOMING) { receiverProgress += it }
            val ok = runBlocking {
                receiver.receiveTransferSession(far.getInputStream(), far.getOutputStream(), request, tracker,
                    direct = receiverLink?.let { options(it, receiverLimits) })
            }
            ok to receiver.failureReason
        }
        val request = requestFor(files, features)
        val sent = inBackground {
            runBlocking {
                FileSender().sendTransferSession(
                    socketInputStream = near.getInputStream(),
                    socketOutputStream = shared,
                    requestPayload = request,
                    openFile = { ByteArrayInputStream(files[it].second) },
                    progressTracker = TransferProgressTracker(request.sessionId, request.totalFiles, request.totalSizeBytes,
                        SessionDirection.OUTGOING) { senderProgress += it },
                    setReadTimeout = { near.soTimeout = it },
                    direct = senderLink?.let { options(it, senderLimits) }
                )
            }
        }.result(40)
        val (received, failure) = receiving.result(40)
        near.close()
        far.close()
        return Outcome(sent, received, failure, storage, shared.written.get(), senderProgress, receiverProgress)
    }

    @Test fun aTransferMovesOntoTheDirectLinkInEveryArrangement() {
        class Case(val senderRank: Int, val senderJoins: Boolean, val receiverRank: Int, val receiverJoins: Boolean,
                   val hostListens: Boolean, val senderHosts: Boolean)
        for (case in listOf(
            Case(0, true, 2, false, hostListens = false, senderHosts = false), // this phone sends; the computer hosts and connects to it
            Case(0, true, 2, false, hostListens = true, senderHosts = false),  // same, but the host accepts the connection
            Case(2, false, 0, true, hostListens = false, senderHosts = true),  // this phone receives; the computer hosts
            Case(2, false, 0, true, hostListens = true, senderHosts = true),
            Case(1, true, 2, true, hostListens = false, senderHosts = false),  // both can host: the higher rank does
            Case(2, true, 1, true, hostListens = true, senderHosts = true)
        )) {
            val name = "sender(${case.senderRank},${case.senderJoins}) receiver(${case.receiverRank},${case.receiverJoins}) hostListens=${case.hostListens}"
            val sender = LoopbackLink(case.senderRank, case.senderJoins).apply { hostListens = case.hostListens }
            val receiver = LoopbackLink(case.receiverRank, case.receiverJoins).apply { hostListens = case.hostListens }
            // The shared connection fails after 16 KiB, far less than the files:
            // this can only succeed if the data really moved to the new connection.
            val outcome = transfer(files, sender, receiver, sharedLimit = 16 * 1024)
            assertNull(name, outcome.sent)
            assertTrue("$name: ${outcome.failure}", outcome.received)
            assertSameFiles(published(files), outcome.storage.publishedFiles())
            assertTrue("$name: ${outcome.sharedBytes} bytes on the shared connection", outcome.sharedBytes < 16 * 1024)

            val (host, joiner) = if (case.senderHosts) sender to receiver else receiver to sender
            assertTrue(name, "host" in host.calls && "join" !in host.calls)
            assertTrue(name, "join" in joiner.calls && "host" !in joiner.calls)
            // Whoever listens, the other side connects.
            assertEquals(name, case.hostListens, "connect" in joiner.calls)
            assertEquals(name, !case.hostListens, "connect" in host.calls)
            // Both links are given back at the end, and only then.
            assertEquals(name, "close", sender.calls.last())
            assertEquals(name, "close", receiver.calls.last())

            // The person is told: first the set-up, then every file "over the direct link".
            for (progress in listOf(outcome.senderProgress, outcome.receiverProgress)) {
                assertTrue(name, progress.any { it.phase == TransferPhase.LINKING })
                assertTrue(name, progress.filter { it.phase == TransferPhase.STREAMING }.let { it.isNotEmpty() && it.all { each -> each.direct } })
                assertTrue(name, progress.indexOfFirst { it.phase == TransferPhase.LINKING } < progress.indexOfFirst { it.direct })
            }
        }
    }

    @Test fun aLinkThatCannotBeSetUpLeavesTheTransferOnTheSharedNetwork() {
        // (the phone sends) and (the phone receives), with the host or the joiner failing.
        for (phoneSends in listOf(true, false)) for ((failHost, failJoin) in listOf(true to false, false to true)) for (hostListens in listOf(false, true)) {
            val name = "phoneSends=$phoneSends failHost=$failHost failJoin=$failJoin hostListens=$hostListens"
            val phone = LoopbackLink(0, true).apply { this.failJoin = failJoin }
            val computer = LoopbackLink(2, false).apply { this.failHost = failHost; this.hostListens = hostListens }
            val outcome = if (phoneSends) transfer(files, phone, computer) else transfer(files, computer, phone)
            assertNull(name, outcome.sent)
            assertTrue("$name: ${outcome.failure}", outcome.received)
            assertFalse(name, outcome.direct)
            assertTrue(name, outcome.sharedBytes > files.sumOf { it.second.size })
            assertTrue("$name: link not released", phone.closed.get() && computer.closed.get())
            assertEquals(name, !failHost, "join" in phone.calls)
            assertFalse(name, "connect" in phone.calls || "connect" in computer.calls)
            assertSameFiles(published(files), outcome.storage.publishedFiles())
            // The set-up was shown, and nothing claims a direct link afterwards.
            assertTrue(name, outcome.senderProgress.any { it.phase == TransferPhase.LINKING })
        }
    }

    @Test fun aDirectLinkIsOnlyUsedWhenBothDevicesOfferAWorkablePairing() {
        // Only one side has a link; two joiners; two hosts that cannot join; a phone with the setting off.
        for ((sender, receiver) in listOf<Pair<Pair<Int, Boolean>?, Pair<Int, Boolean>?>>(
            (0 to true) to null,
            null to (2 to false),
            (0 to true) to (0 to true),
            (2 to false) to (2 to false),
            (0 to false) to (2 to false),
            (2 to false) to (0 to false)
        )) {
            val name = "sender=$sender receiver=$receiver"
            val senderLink = sender?.let { LoopbackLink(it.first, it.second) }
            val receiverLink = receiver?.let { LoopbackLink(it.first, it.second) }
            val outcome = transfer(small, senderLink, receiverLink)
            assertNull(name, outcome.sent)
            assertTrue(name, outcome.received)
            assertFalse(name, outcome.direct)
            for (link in listOfNotNull(senderLink, receiverLink)) assertEquals(name, listOf("close"), steps(link))
            assertFalse(name, (outcome.senderProgress + outcome.receiverProgress).any { it.phase == TransferPhase.LINKING })
            assertSameFiles(published(small), outcome.storage.publishedFiles())
        }
    }

    @Test fun aSenderWithoutBatchIsNeverOfferedOrGrantedADirectLink() {
        val sender = LoopbackLink(0, true)
        val receiver = LoopbackLink(2, false)
        val outcome = transfer(small, sender, receiver, features = emptyList())
        assertNull(outcome.sent)
        assertTrue(outcome.received)
        assertFalse(outcome.direct)
        assertEquals(listOf("close"), steps(sender))
        assertEquals(listOf("close"), steps(receiver))
    }

    @Test fun aHostWhoseJoinerNeverArrivesGivesUpAndBothStayOnTheSharedNetwork() {
        // The joiner's radio takes forever; it keeps the connection alive meanwhile.
        class Endless : LoopbackLink(0, true) {
            override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
                calls += "join"
                while (true) {
                    tick()
                    Thread.sleep(20)
                }
            }
        }
        for (hostListens in listOf(true, false)) {
            val joiner = Endless()
            val host = LoopbackLink(2, false).apply { this.hostListens = hostListens }
            val outcome = transfer(small, joiner, host, receiverLimits = fast.copy(joinMs = 300))
            assertNull("hostListens=$hostListens", outcome.sent)
            assertTrue("hostListens=$hostListens: ${outcome.failure}", outcome.received)
            assertFalse(outcome.direct)
            assertTrue(joiner.closed.get() && host.closed.get())
            assertSameFiles(published(small), outcome.storage.publishedFiles())
        }
    }

    // ── This phone sends and joins: the exact exchange with a host ──────────

    @Test fun aSendingPhoneOffersToJoinListensAndStreamsOnTheNewConnection() {
        val link = LoopbackLink(0, true)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH_AND_RESUME), files, direct = options(link))
            val request = peer.expectJson(FrameTag.TRANSFER_REQUEST)
            assertEquals(listOf("batch", "resume", "direct"), request.features())
            assertEquals(0, request.getJSONObject("direct").getInt("hostRank"))
            assertTrue(request.getJSONObject("direct").getBoolean("join"))
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch","resume","direct"],"directHost":"receiver"}""")
            peer.write(FrameTag.RESUME_STATE, "[]")
            // The host is still creating its network; keep-alives arrive meanwhile and nothing is sent yet.
            peer.write(FrameTag.HEARTBEAT)
            peer.assertQuiet()
            assertEquals(emptyList<String>(), steps(link))
            // A Windows host: no hostPort, so the phone listens and says where.
            peer.write(FrameTag.DIRECT_OFFER, offer())
            val joined = peer.expectJson(FrameTag.DIRECT_JOINED)
            assertEquals("127.0.0.1", joined.getString("address"))

            // Someone else on the network: a wrong token gets no answer and is dropped.
            connectAsHost(joined, shown = "f".repeat(32)).use { stranger -> assertTrue(stranger.atEnd()) }
            // Someone who does not even speak the protocol.
            Peer(Socket(joined.getString("address"), joined.getInt("port"))).use { stranger ->
                stranger.output.write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(stranger.atEnd())
            }
            peer.assertQuiet(100)
            assertFalse(sent.isDone)

            connectAsHost(joined).use { direct ->
                assertEquals("OK", String(direct.expect(FrameTag.DIRECT_RESULT)))
                direct.expectBatchFiles(files)
                // Nothing more was sent on the shared connection, and the link is still held.
                peer.assertQuiet(100)
                assertFalse(sent.isDone)
                assertFalse(link.closed.get())
                direct.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
                assertNull(sent.result())
                assertTrue(direct.atEnd())
            }
            assertEquals(listOf("join", "close"), steps(link))
        }
        near.close()
    }

    @Test fun aSendingPhoneConnectsToAHostThatListens() {
        val link = LoopbackLink(0, true)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH), files, direct = options(link))
            assertEquals(listOf("batch", "direct"), peer.expectJson(FrameTag.TRANSFER_REQUEST).features())
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                peer.write(FrameTag.DIRECT_OFFER, offer(hostPort = server.localPort))
                acceptAsHost(server).use { direct ->
                    direct.expectBatchFiles(files)
                    // The phone had nothing to report on the shared connection.
                    peer.assertQuiet(100)
                    direct.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
                    assertNull(sent.result())
                }
            }
            assertEquals(listOf("join", "connect", "close"), steps(link))
        }
        near.close()
    }

    @Test fun theSharedConnectionIsKeptAliveWhileThePhoneJoins() {
        // The system asks the owner first; that can take a while.
        class Asking : LoopbackLink(0, true) {
            override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
                val until = System.nanoTime() + 2_600L * 1_000_000
                while (System.nanoTime() < until) {
                    tick()
                    Thread.sleep(50)
                }
                return super.join(offer, tick)
            }
        }
        val link = Asking()
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(small, BATCH), small, direct = options(link))
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            var keepAlives = 0
            while (true) {
                val (tag, payload) = peer.read()
                if (tag == FrameTag.HEARTBEAT) {
                    keepAlives++
                    // The host answers in kind; that must not disturb the join.
                    peer.write(FrameTag.HEARTBEAT)
                    continue
                }
                assertEquals(FrameTag.DIRECT_JOINED, tag)
                connectAsHost(JSONObject(String(payload))).use { direct ->
                    assertEquals("OK", String(direct.expect(FrameTag.DIRECT_RESULT)))
                    direct.expectBatchFiles(small)
                    direct.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
                    assertNull(sent.result())
                }
                break
            }
            assertTrue("keep-alives while joining: $keepAlives", keepAlives >= 1)
        }
        near.close()
    }

    // ── This phone sends and joins: every way back to the shared network ────

    /** Runs a sender up to the point where the recipient has granted the link with itself as host. */
    private fun granted(link: DirectLink, limits: LinkLimits = fast, files: List<TestFile> = small, test: (Peer, Future<String?>) -> Unit) {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH), files, direct = options(link, limits))
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            test(peer, sent)
        }
        near.close()
    }

    /** The transfer goes on where it was, as if `direct` had not been granted. */
    private fun expectSharedStream(peer: Peer, sent: Future<String?>, files: List<TestFile> = small) {
        peer.expectBatchFiles(files)
        assertFalse(sent.isDone)
        peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
        assertNull(sent.result())
    }

    @Test fun aHostThatReportsFailureLeavesTheSenderOnTheSharedConnection() {
        val link = LoopbackLink(0, true)
        granted(link) { peer, sent ->
            peer.write(FrameTag.HEARTBEAT)
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: this adapter cannot create a network")
            // No reply to the failure: the files follow at once.
            expectSharedStream(peer, sent)
        }
        assertEquals(listOf("close"), steps(link))
    }

    @Test fun aPhoneThatCannotJoinSaysSoAndStaysOnTheSharedConnection() {
        val link = LoopbackLink(0, true).apply { failJoin = true }
        granted(link) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            assertEquals("FAILED: could not join the network", failure(peer))
            expectSharedStream(peer, sent)
        }
        assertEquals(listOf("join", "close"), steps(link))
    }

    @Test fun anOfferThatDoesNotArriveInTimeIsGivenUp() {
        val link = LoopbackLink(0, true)
        granted(link, fast.copy(offerMs = 300)) { peer, sent ->
            // The host keeps the connection alive but never gets its network up.
            val started = System.nanoTime()
            val beating = inBackground { repeat(8) { peer.write(FrameTag.HEARTBEAT); Thread.sleep(100) } }
            assertEquals("FAILED: the other device did not offer a link in time", failure(peer))
            assertTrue((System.nanoTime() - started) / 1_000_000 in 250..2_000)
            beating.result()
            expectSharedStream(peer, sent)
        }
        assertEquals(listOf("close"), steps(link))
    }

    @Test fun anOfferThatCannotBeReadIsGivenUp() {
        for (bad in listOf("not json", """{"ssid":"x"}""", """{"ssid":"x","passphrase":"y","token":"t","hostPort":0}""")) {
            val link = LoopbackLink(0, true)
            granted(link) { peer, sent ->
                peer.write(FrameTag.DIRECT_OFFER, bad)
                assertEquals("FAILED: the link offer could not be read", failure(peer))
                expectSharedStream(peer, sent)
            }
            assertEquals(listOf("close"), steps(link))
        }
    }

    @Test fun aHostThatNeverConnectsIsGivenUpAndItsLateAnswerIsIgnored() {
        val link = LoopbackLink(0, true)
        granted(link, fast.copy(connectMs = 300), files) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            val joined = peer.expectJson(FrameTag.DIRECT_JOINED)
            val started = System.nanoTime()
            assertEquals("FAILED: the other device did not connect in time", failure(peer))
            assertTrue((System.nanoTime() - started) / 1_000_000 in 250..2_000)
            // Both sides gave up at about the same time: the host's own report crosses ours.
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: the other device did not join in time")
            // The phone no longer listens on the link.
            assertThrows(IOException::class.java) { Socket(joined.getString("address"), joined.getInt("port")).close() }
            expectSharedStream(peer, sent, files)
        }
        assertEquals(listOf("join", "close"), steps(link))
    }

    @Test fun aHostThatGivesUpWhileThePhoneListensEndsTheWaitWithoutAReply() {
        val link = LoopbackLink(0, true)
        granted(link) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            peer.expect(FrameTag.DIRECT_JOINED)
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: no route to the other device")
            // The next frame is already the first file.
            expectSharedStream(peer, sent)
        }
        assertTrue(link.closed.get())
    }

    @Test fun aListeningHostThatDoesNotAnswerTheGreetingIsGivenUp() {
        val link = LoopbackLink(0, true)
        granted(link, fast.copy(greetingMs = 300)) { peer, sent ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                peer.write(FrameTag.DIRECT_OFFER, offer(hostPort = server.localPort))
                server.soTimeout = 10_000
                server.accept().use { silent ->
                    assertEquals("FAILED: the direct link did not answer in time", failure(peer))
                    // The phone closed its end.
                    silent.soTimeout = 5_000
                    val input = silent.getInputStream()
                    while (input.read() != -1) Unit
                }
            }
            expectSharedStream(peer, sent)
        }
        assertEquals(listOf("join", "connect", "close"), steps(link))
    }

    @Test fun aHostThatCannotBeReachedOrRefusesTheTokenIsGivenUp() {
        // Nobody listens on the port the host named.
        val unreachable = LoopbackLink(0, true)
        granted(unreachable) { peer, sent ->
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            peer.write(FrameTag.DIRECT_OFFER, offer(hostPort = port))
            assertEquals("FAILED: the link could not be set up", failure(peer))
            expectSharedStream(peer, sent)
        }
        assertTrue(unreachable.closed.get())

        // The host answers the token with something other than OK.
        val refused = LoopbackLink(0, true)
        granted(refused) { peer, sent ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                peer.write(FrameTag.DIRECT_OFFER, offer(hostPort = server.localPort))
                server.soTimeout = 10_000
                Peer(server.accept()).use { direct ->
                    direct.expect(FrameTag.HEARTBEAT)
                    direct.write(FrameTag.HEARTBEAT, Protocol.HELLO_ACK)
                    direct.expect(FrameTag.DIRECT_RESULT)
                    direct.write(FrameTag.DIRECT_RESULT, "FAILED: unknown token")
                    assertEquals("FAILED: the other device did not confirm the direct link", failure(peer))
                }
            }
            expectSharedStream(peer, sent)
        }
        assertTrue(refused.closed.get())
    }

    @Test fun anUnexpectedFrameDuringSetUpIsReportedAsAFailedLink() {
        val link = LoopbackLink(0, true)
        granted(link) { peer, _ ->
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertEquals("FAILED: unexpected TRANSFER_ACK frame", failure(peer))
        }
        assertTrue(link.closed.get())
    }

    // ── Only what was offered, and only with batch ──────────────────────────

    /** The request a sender puts on the wire with this link. */
    private fun requestWith(link: DirectLink?, features: List<String> = BATCH_AND_RESUME): JSONObject {
        val (near, far) = socketPair()
        val request = Peer(far).use { peer ->
            val sent = startSender(near, requestFor(small, features), small, direct = link?.let { options(it) })
            peer.expectJson(FrameTag.TRANSFER_REQUEST).also {
                peer.write(FrameTag.TRANSFER_REJECT, """{"status":"REJECTED","reason":"declined"}""")
                assertEquals("Transfer declined by recipient", sent.result())
            }
        }
        near.close()
        return request
    }

    @Test fun nothingIsOfferedWhenTheSettingIsOffOrThePhoneCannotJoin() {
        // The setting is off: the engine has no link at all.
        val off = requestWith(null)
        assertEquals(BATCH_AND_RESUME, off.features())
        assertFalse(off.has("direct"))

        // The setting is on, but this phone would have to give up its own Wi-Fi, or runs Android 11.
        for (caps in listOf(
            DirectLinkSupport.caps(enabled = true, sdk = 34, keepsWifi = false),
            DirectLinkSupport.caps(enabled = true, sdk = 30, keepsWifi = true),
            DirectLinkSupport.caps(enabled = false, sdk = 34, keepsWifi = true)
        )) {
            val link = LoopbackLink(caps.hostRank, caps.join)
            val request = requestWith(link)
            assertEquals(BATCH_AND_RESUME, request.features())
            assertFalse(request.has("direct"))
            // Even an unused link is given back.
            assertEquals(listOf("close"), steps(link))
        }

        // Everything holds: the phone offers to join, never to host.
        val caps = DirectLinkSupport.caps(enabled = true, sdk = 34, keepsWifi = true)
        val request = requestWith(LoopbackLink(caps.hostRank, caps.join))
        assertEquals(listOf("batch", "resume", "direct"), request.features())
        assertEquals(0, request.getJSONObject("direct").getInt("hostRank"))
        assertTrue(request.getJSONObject("direct").getBoolean("join"))
    }

    @Test fun nothingIsOfferedWithoutBatch() {
        val link = LoopbackLink(0, true)
        // The original request, byte for byte: no features and no direct object.
        val request = requestWith(link, features = emptyList())
        assertNull(request.features())
        assertFalse(request.has("direct"))
        assertEquals(listOf("close"), steps(link))
        // Resume alone is not batch either.
        assertFalse(requestWith(LoopbackLink(0, true), features = listOf(Protocol.FEATURE_RESUME)).has("direct"))
    }

    @Test fun aSenderFollowsOnlyARoleItOfferedAndOnlyWithBatch() {
        // Named as host although it cannot host; granted without a host; a host named without the grant.
        for (accept in listOf(
            """{"status":"ACCEPTED","features":["batch","direct"],"directHost":"sender"}""",
            """{"status":"ACCEPTED","features":["batch","direct"]}""",
            """{"status":"ACCEPTED","features":["batch","direct"],"directHost":"both"}""",
            """{"status":"ACCEPTED","features":["batch"],"directHost":"receiver"}"""
        )) {
            val link = LoopbackLink(0, true)
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val sent = startSender(near, requestFor(small, BATCH), small, direct = options(link))
                peer.expect(FrameTag.TRANSFER_REQUEST)
                peer.write(FrameTag.TRANSFER_ACCEPT, accept)
                // No set-up: the files follow at once on the shared connection.
                expectSharedStream(peer, sent)
            }
            near.close()
            assertEquals(accept, listOf("close"), steps(link))
        }

        // A link granted without batch is not accepted: this is the original exchange.
        val link = LoopbackLink(0, true)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(small, BATCH), small, direct = options(link))
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["direct"],"directHost":"receiver"}""")
            peer.expect(FrameTag.FILE_HEADER)
            // The sender waits for the per-file acknowledgement, not for an offer.
            peer.assertQuiet()
            peer.write(FrameTag.TRANSFER_ACK, FILE_ACK)
            assertArrayEquals(small[0].second, peer.expectData(small[0].second.size))
            peer.expect(FrameTag.FILE_COMPLETE)
            peer.expect(FrameTag.TRANSFER_COMPLETE)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
        assertEquals(listOf("close"), steps(link))
    }

    /** The acceptance a receiver with this link sends for [request]. */
    private fun acceptFor(request: TransferRequestPayload, link: DirectLink?): JSONObject {
        val storage = FakeStorage(folder.newFolder())
        val (near, far) = socketPair()
        val accept = Peer(far).use { peer ->
            val run = startReceiver(near, request, storage, link?.let { options(it) })
            peer.expectJson(FrameTag.TRANSFER_ACCEPT).also {
                // The sender starts its files at once: no set-up was agreed.
                peer.write(FrameTag.TRANSFER_CANCEL, request.sessionId)
                assertFalse(run.done.result())
            }
        }
        near.close()
        return accept
    }

    @Test fun aReceiverGrantsADirectLinkOnlyForAWorkablePairingWithBatch() {
        val computer = hostingRequest(small)
        // Nothing to offer on this phone: the setting is off, or it cannot keep its Wi-Fi.
        for (link in listOf(null, LoopbackLink(0, false))) {
            val accept = acceptFor(computer, link)
            assertEquals(setOf("status", "features"), accept.keySet())
            assertEquals(listOf("batch"), accept.features())
            link?.let { assertEquals(listOf("close"), steps(it)) }
        }
        // The sender did not offer it, offered it without saying what it can do, or cannot host either.
        for (request in listOf(
            requestFor(small, BATCH_AND_RESUME).copy(direct = DirectCaps(2, false)),
            requestFor(small, withDirect),
            requestFor(small, withDirect).copy(direct = DirectCaps(0, true)),
            requestFor(small, withDirect).copy(direct = DirectCaps(0, false))
        )) {
            val link = LoopbackLink(0, true)
            val accept = acceptFor(request, link)
            assertEquals(setOf("status", "features"), accept.keySet())
            assertEquals(listOf("batch"), accept.features())
            assertEquals(listOf("close"), steps(link))
        }
        // Without batch the reply is the original one, whatever else was offered.
        val link = LoopbackLink(0, true)
        val old = acceptFor(requestFor(small, listOf(Protocol.FEATURE_DIRECT)).copy(direct = DirectCaps(2, false)), link)
        assertEquals(setOf("status", "resumeOffset"), old.keySet())
        assertEquals(listOf("close"), steps(link))
    }

    // ── This phone receives and joins ───────────────────────────────────────

    @Test fun aReceivingPhoneNamesTheSenderAsHostJoinsAndReceivesOnTheNewConnection() {
        for (hostListens in listOf(false, true)) {
            val storage = FakeStorage(folder.newFolder())
            val link = LoopbackLink(0, true)
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val run = startReceiver(near, hostingRequest(files), storage, options(link))
                val accept = peer.expectJson(FrameTag.TRANSFER_ACCEPT)
                // Batch and direct; resume is not granted by this phone, so no RESUME_STATE follows.
                assertEquals(setOf("status", "features", "directHost"), accept.keySet())
                assertEquals(listOf("batch", "direct"), accept.features())
                assertEquals("sender", accept.getString("directHost"))
                peer.write(FrameTag.HEARTBEAT)
                peer.assertQuiet()

                val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
                val direct = server.use {
                    if (hostListens) {
                        peer.write(FrameTag.DIRECT_OFFER, offer(hostPort = server.localPort))
                        acceptAsHost(server)
                    } else {
                        peer.write(FrameTag.DIRECT_OFFER, offer())
                        connectAsHost(peer.expectJson(FrameTag.DIRECT_JOINED)).also {
                            assertEquals("OK", String(it.expect(FrameTag.DIRECT_RESULT)))
                        }
                    }
                }
                direct.use {
                    direct.sendBatchFiles(files)
                    assertTrue(storage.publishedFiles().isEmpty())
                    direct.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
                    // The confirmation comes on the new connection, after everything was saved.
                    assertEquals("ACK", JSONObject(String(direct.await(FrameTag.TRANSFER_ACK))).getString("status"))
                    assertSameFiles(published(files), storage.publishedFiles())
                    // The network is held until the sender has read the confirmation and closed.
                    Thread.sleep(300)
                    assertFalse(run.done.isDone)
                    assertFalse(link.closed.get())
                }
                assertTrue(run.done.result())
                assertTrue(link.closed.get())
                // Nothing but the set-up ever went over the shared connection.
                assertEquals(0, peer.input.available())
            }
            near.close()
            assertEquals(if (hostListens) listOf("join", "connect", "close") else listOf("join", "close"), steps(link))
        }
    }

    @Test fun aReceivingPhoneStaysOnTheSharedConnectionWhenTheLinkFails() {
        // The host fails; the phone cannot join; the host never connects.
        for (case in listOf("host", "join", "connect")) {
            val storage = FakeStorage(folder.newFolder())
            val link = LoopbackLink(0, true).apply { failJoin = case == "join" }
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val run = startReceiver(near, hostingRequest(small), storage, options(link, fast.copy(connectMs = 300)))
                assertEquals("sender", peer.expectJson(FrameTag.TRANSFER_ACCEPT).getString("directHost"))
                when (case) {
                    "host" -> peer.write(FrameTag.DIRECT_RESULT, "FAILED: this adapter cannot create a network")
                    "join" -> {
                        peer.write(FrameTag.DIRECT_OFFER, offer())
                        assertEquals("FAILED: could not join the network", failure(peer))
                    }
                    else -> {
                        peer.write(FrameTag.DIRECT_OFFER, offer())
                        peer.expect(FrameTag.DIRECT_JOINED)
                        assertEquals("FAILED: the other device did not connect in time", failure(peer))
                    }
                }
                peer.sendBatchFiles(small)
                // A report that crossed ours on the way is not part of the stream.
                peer.write(FrameTag.DIRECT_RESULT, "FAILED: the other device did not join in time")
                peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
                assertEquals("ACK", JSONObject(String(peer.await(FrameTag.TRANSFER_ACK))).getString("status"))
                assertTrue(case, run.done.result())
            }
            near.close()
            assertTrue(case, link.closed.get())
            assertSameFiles(published(small), storage.publishedFiles())
        }
    }

    // ── A late DIRECT_RESULT is a keep-alive ────────────────────────────────

    @Test fun aStrayDirectResultIsIgnoredBySenderAndReceiver() {
        // Sending: while streaming (seen by the peer check) and while waiting for the final acknowledgement.
        val big: List<TestFile> = listOf("big.bin" to randomBytes(3 * 1024 * 1024, 5))
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(big, BATCH), big, openFile = { SlowInput(big[it].second, 10) })
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: too late")
            peer.expectBatchFiles(big)
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: too late")
            peer.assertQuiet()
            assertFalse(sent.isDone)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()

        // Receiving: between any two frames of a batch session.
        val storage = FakeStorage(folder.newFolder())
        val (here, there) = socketPair()
        Peer(there).use { peer ->
            val run = startReceiver(here, requestFor(small, BATCH), storage, direct = null)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: too late")
            peer.sendBatchFiles(small)
            peer.write(FrameTag.DIRECT_RESULT)
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            assertEquals("ACK", JSONObject(String(peer.await(FrameTag.TRANSFER_ACK))).getString("status"))
            assertTrue(run.done.result())
        }
        here.close()
        assertSameFiles(published(small), storage.publishedFiles())
    }

    @Test fun aDirectResultIsNotPartOfTheOriginalExchange() {
        val storage = FakeStorage(folder.newFolder())
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(small), storage, direct = null)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: too late")
            assertFalse(run.done.result())
            assertTrue(run.receiver.failureReason!!.contains("Expected FILE_HEADER"))
        }
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    // ── Cancelling and failing, with the link released every time ───────────

    /** A radio that takes its time: the join never finishes on its own. */
    private class Joining : LoopbackLink(0, true) {
        val started = AtomicBoolean(false)
        override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
            calls += "join"
            started.set(true)
            while (true) {
                tick()
                Thread.sleep(20)
            }
        }

        fun awaitStart() {
            val giveUp = System.nanoTime() + 10_000L * 1_000_000
            while (!started.get()) {
                check(System.nanoTime() < giveUp) { "the join never started" }
                Thread.sleep(10)
            }
            Thread.sleep(100)
        }
    }

    private fun connect(direction: SessionDirection): Pair<TransferConnection, Peer> {
        val (near, far) = socketPair()
        val session = TransferSession(peerUuid = "peer", peerName = "Peer", peerAddress = "127.0.0.1", peerPort = far.localPort,
            direction = direction)
        return TransferConnection(near, session, onSessionStateChanged = {}) to Peer(far)
    }

    /** The same wiring as TransferEngine: the connection adopts the link's socket. */
    private fun startSender(
        connection: TransferConnection,
        files: List<TestFile>,
        link: DirectLink,
        limits: LinkLimits = fast,
        openFile: (Int) -> InputStream = { ByteArrayInputStream(files[it].second) }
    ) = inBackground {
        val request = requestFor(files, BATCH)
        runBlocking {
            FileSender().sendTransferSession(
                socketInputStream = checkNotNull(connection.inputStream),
                socketOutputStream = checkNotNull(connection.outputStream),
                requestPayload = request,
                openFile = openFile,
                progressTracker = trackerFor(request, SessionDirection.OUTGOING),
                setReadTimeout = { connection.readTimeoutMs = it },
                isCancelled = { connection.isCancelled },
                sendNotice = { tag, payload -> connection.sendNotice(tag, payload, drain = true) },
                direct = DirectOptions(link, connection::adopt, limits)
            ).also { connection.close() }
        }
    }

    /** The same wiring as TransferConnection.receiveRequest. */
    private fun startReceiver(connection: TransferConnection, request: TransferRequestPayload, storage: FakeStorage, link: DirectLink): Receive {
        val receiver = FileReceiver(storage)
        return Receive(receiver, inBackground {
            runBlocking {
                receiver.receiveTransferSession(
                    checkNotNull(connection.inputStream), checkNotNull(connection.outputStream), request,
                    trackerFor(request, SessionDirection.INCOMING),
                    isCancelled = { connection.isCancelled },
                    sendNotice = { tag, payload -> connection.sendNotice(tag, payload, drain = true) },
                    direct = DirectOptions(link, connection::adopt, fast)
                ).also { connection.close() }
            }
        })
    }

    @Test fun cancellingWhileThePhoneJoinsEndsTheTransferAndReleasesTheLink() {
        val link = Joining()
        val (connection, peer) = connect(SessionDirection.OUTGOING)
        peer.use {
            val sent = startSender(connection, small, link)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            link.awaitStart()
            val started = System.nanoTime()
            connection.cancelTransfer()
            // The host learns it on the shared connection, at a frame boundary.
            assertEquals("tx-test-session", String(peer.await(FrameTag.TRANSFER_CANCEL)))
            assertTrue(peer.atEnd())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            peer.close()
            assertEquals("Transfer cancelled", sent.result())
        }
        assertEquals(listOf("join", "close"), steps(link))
    }

    @Test fun cancellingWhileWaitingForTheOfferEndsTheTransfer() {
        val link = LoopbackLink(0, true)
        val (connection, peer) = connect(SessionDirection.OUTGOING)
        peer.use {
            val sent = startSender(connection, small, link)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            // The sender is blocked reading the offer.
            peer.assertQuiet()
            connection.cancelTransfer()
            assertEquals("tx-test-session", String(peer.expect(FrameTag.TRANSFER_CANCEL)))
            peer.close()
            assertEquals("Transfer cancelled", sent.result())
        }
        assertEquals(listOf("close"), steps(link))
    }

    @Test fun aCancelFromTheOtherDeviceDuringSetUpEndsTheTransfer() {
        // While this phone waits for the offer, and while it is joining.
        for (joining in listOf(false, true)) {
            val link = Joining()
            granted(link) { peer, sent ->
                if (joining) {
                    peer.write(FrameTag.DIRECT_OFFER, offer())
                    link.awaitStart()
                }
                peer.write(FrameTag.TRANSFER_CANCEL)
                assertEquals("Transfer cancelled by recipient", sent.result())
                // Nothing is sent back: no failure report and no cancel of our own.
                assertEquals(0, peer.input.available())
            }
            assertEquals("joining=$joining", "close", link.calls.last())
            assertEquals(joining, "join" in link.calls)
        }

        // An error is reported with its reason, like anywhere else in a batch session.
        val link = Joining()
        granted(link) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            link.awaitStart()
            peer.write(FrameTag.ERROR, "not enough storage space")
            assertEquals("The other device stopped the transfer: not enough storage space", sent.result())
        }
        assertTrue(link.closed.get())
    }

    @Test fun aHostThatGivesUpWhileThePhoneJoinsStopsTheJoin() {
        val link = Joining()
        granted(link) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            link.awaitStart()
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: the other device did not join in time")
            // The join is abandoned without a report of our own; the files follow.
            expectSharedStream(peer, sent)
        }
        assertEquals(listOf("join", "close"), steps(link))
    }

    @Test fun aHostThatGaveUpJustBeforeTheJoinFinishedIsNotToldWhereToConnect() {
        // The radio finishes without another look at the shared connection.
        class Late : LoopbackLink(0, true) {
            val joining = AtomicBoolean(false)
            val finish = AtomicBoolean(false)
            override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
                calls += "join"
                joining.set(true)
                while (!finish.get()) Thread.sleep(10)
                return JoinedLink("127.0.0.1", listen())
            }
        }
        val link = Late()
        granted(link) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            while (!link.joining.get()) Thread.sleep(10)
            // The host stops waiting and, as the receiver, now expects the files here.
            peer.write(FrameTag.HEARTBEAT)
            peer.write(FrameTag.DIRECT_RESULT, "FAILED: the other device did not join in time")
            Thread.sleep(200)
            link.finish.set(true)
            // No DIRECT_JOINED, which the host would take for a broken stream.
            expectSharedStream(peer, sent)
        }
        assertEquals(listOf("join", "close"), steps(link))
    }

    @Test fun aReceivingPhoneCancelsOrIsCancelledDuringSetUp() {
        // The person on this phone cancels while it joins.
        val storage = FakeStorage(folder.newFolder())
        val link = Joining()
        val (connection, peer) = connect(SessionDirection.INCOMING)
        peer.use {
            val run = startReceiver(connection, hostingRequest(small), storage, link)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            link.awaitStart()
            connection.cancelTransfer()
            assertEquals("tx-test-session", String(peer.await(FrameTag.TRANSFER_CANCEL)))
            peer.close()
            assertFalse(run.done.result())
            assertEquals("Transfer cancelled", run.receiver.failureReason)
        }
        assertEquals(listOf("join", "close"), steps(link))
        assertEquals(emptyList<String>(), storage.leftovers())

        // The sender cancels while this phone joins: recorded as cancelled by the sender.
        val other = Joining()
        val (near, far) = socketPair()
        Peer(far).use { sender ->
            val run = startReceiver(near, hostingRequest(small), storage, options(other))
            sender.expect(FrameTag.TRANSFER_ACCEPT)
            sender.write(FrameTag.DIRECT_OFFER, offer())
            other.awaitStart()
            sender.write(FrameTag.TRANSFER_CANCEL)
            assertFalse(run.done.result())
            assertEquals("Transfer cancelled by sender", run.receiver.failureReason)
        }
        near.close()
        assertEquals(listOf("join", "close"), steps(other))
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun cancellingAfterTheMoveTellsTheOtherDeviceOnTheNewConnection() {
        val big: List<TestFile> = listOf("big.bin" to randomBytes(8 * 1024 * 1024, 4))
        val link = LoopbackLink(0, true)
        val (connection, peer) = connect(SessionDirection.OUTGOING)
        peer.use {
            val sent = startSender(connection, big, link) { SlowInput(big[it].second, 20) }
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            connectAsHost(peer.expectJson(FrameTag.DIRECT_JOINED)).use { direct ->
                assertEquals("OK", String(direct.expect(FrameTag.DIRECT_RESULT)))
                direct.expect(FrameTag.FILE_HEADER)
                direct.expect(FrameTag.DATA_CHUNK)
                connection.cancelTransfer()
                // Whole frames, then the cancel as the last one, on the direct connection.
                val tags = mutableListOf<FrameTag>()
                while (true) {
                    val header = try {
                        FrameUtils.readHeader(direct.input)
                    } catch (_: java.io.EOFException) {
                        break
                    }
                    FrameUtils.readPayload(direct.input, header.payloadLength)
                    tags += header.tag
                }
                assertEquals(FrameTag.TRANSFER_CANCEL, tags.last())
                assertTrue(tags.dropLast(1).all { it == FrameTag.DATA_CHUNK })
            }
            assertEquals("Transfer cancelled", sent.result())
            // The shared connection carried no notice and nothing else.
            assertEquals(0, peer.input.available())
        }
        assertEquals(listOf("join", "close"), steps(link))
    }

    @Test fun aReceivingPhoneCancelsAfterTheMoveOnTheNewConnection() {
        val storage = FakeStorage(folder.newFolder())
        val link = LoopbackLink(0, true)
        val (connection, peer) = connect(SessionDirection.INCOMING)
        peer.use {
            val run = startReceiver(connection, hostingRequest(files), storage, link)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            connectAsHost(peer.expectJson(FrameTag.DIRECT_JOINED)).use { direct ->
                assertEquals("OK", String(direct.expect(FrameTag.DIRECT_RESULT)))
                direct.write(FrameTag.FILE_HEADER, FileHeaderPayload(0, files[0].first, files[0].second.size.toLong()).toJsonObject().toString())
                FrameUtils.writeFrame(direct.output, FrameTag.DATA_CHUNK, files[0].second.copyOf(1000))
                direct.assertQuiet()
                // The phone is blocked reading the new connection; the read wakes for the cancel.
                connection.cancelTransfer()
                assertEquals("tx-test-session", String(direct.await(FrameTag.TRANSFER_CANCEL)))
            }
            assertFalse(run.done.result())
            assertEquals("Transfer cancelled", run.receiver.failureReason)
            assertEquals(0, peer.input.available())
        }
        assertEquals(listOf("join", "close"), steps(link))
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun aDirectConnectionThatBreaksFailsTheTransferAndReleasesTheLink() {
        val big: List<TestFile> = listOf("big.bin" to randomBytes(8 * 1024 * 1024, 4))
        val link = LoopbackLink(0, true)
        granted(link, files = big) { peer, sent ->
            peer.write(FrameTag.DIRECT_OFFER, offer())
            connectAsHost(peer.expectJson(FrameTag.DIRECT_JOINED)).use { direct ->
                assertEquals("OK", String(direct.expect(FrameTag.DIRECT_RESULT)))
                direct.expect(FrameTag.FILE_HEADER)
            }
            // The transfer does not quietly restart on the shared connection.
            assertEquals("Connection to recipient was lost", sent.result())
            assertEquals(0, peer.input.available())
        }
        assertEquals(listOf("join", "close"), steps(link))

        // The same while receiving: nothing is saved.
        val storage = FakeStorage(folder.newFolder())
        val receiving = LoopbackLink(0, true)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, hostingRequest(files), storage, options(receiving))
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            connectAsHost(peer.expectJson(FrameTag.DIRECT_JOINED)).use { direct ->
                assertEquals("OK", String(direct.expect(FrameTag.DIRECT_RESULT)))
                direct.write(FrameTag.FILE_HEADER, FileHeaderPayload(0, files[0].first, files[0].second.size.toLong()).toJsonObject().toString())
                FrameUtils.writeFrame(direct.output, FrameTag.DATA_CHUNK, files[0].second.copyOf(1000))
            }
            assertFalse(run.done.result())
        }
        near.close()
        assertEquals(listOf("join", "close"), steps(receiving))
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun aSharedConnectionThatDiesDuringSetUpFailsTheTransferAndReleasesTheLink() {
        val link = Joining()
        val (near, far) = socketPair()
        val sent = startSender(near, requestFor(small, BATCH), small, direct = options(link))
        Peer(far).use { peer ->
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, grantBoth)
            peer.write(FrameTag.DIRECT_OFFER, offer())
            link.awaitStart()
        }
        // The keep-alive notices within a few seconds; nothing is left requested.
        assertTrue(sent.result()!!.startsWith("Connection to recipient"))
        near.close()
        assertEquals(listOf("join", "close"), steps(link))
    }
}
