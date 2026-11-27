package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Drives FileSender against a scripted peer over a loopback socket and checks the exact frame order. */
class FileSenderTest {
    private val mixed: List<TestFile> = listOf(
        "notes.txt" to randomBytes(10, 1),
        "photo.jpg" to randomBytes(300_000, 2),
        "empty.bin" to ByteArray(0),
        "video.mp4" to randomBytes(700_000, 3)
    )

    private fun JSONObject.features(): List<String>? =
        optJSONArray("features")?.let { list -> (0 until list.length()).map { list.getString(it) } }

    /** The original exchange: one acknowledgement per file header, and the sender waits for it. */
    private fun expectDfp1Stream(peer: Peer, files: List<TestFile>) {
        for ((index, file) in files.withIndex()) {
            val header = peer.expectJson(FrameTag.FILE_HEADER)
            assertEquals(index, header.getInt("fileIndex"))
            assertEquals(file.first, header.getString("relativePath"))
            assertEquals(file.second.size.toLong(), header.getLong("sizeBytes"))
            assertEquals(0L, header.getLong("resumeOffset"))
            // No data until the acknowledgement.
            peer.assertQuiet()
            peer.write(FrameTag.TRANSFER_ACK, FILE_ACK)
            assertArrayEquals(file.second, peer.expectData(file.second.size))
            val complete = peer.expectJson(FrameTag.FILE_COMPLETE)
            assertEquals(index, complete.getInt("fileIndex"))
            assertEquals(file.second.size.toLong(), complete.getLong("bytesWritten"))
            assertEquals(sha256(file.second), complete.getString("sha256Checksum"))
        }
        assertEquals("tx-test-session", String(peer.expect(FrameTag.TRANSFER_COMPLETE)))
    }

    /** Batch mode: the whole stream arrives although the peer sends nothing after the accept. */
    private fun expectBatchStream(peer: Peer, files: List<TestFile>, offsets: Map<Int, Int> = emptyMap()) {
        for ((index, file) in files.withIndex()) {
            val offset = offsets[index] ?: 0
            val header = peer.expectJson(FrameTag.FILE_HEADER)
            assertEquals(index, header.getInt("fileIndex"))
            assertEquals(file.first, header.getString("relativePath"))
            assertEquals(file.second.size.toLong(), header.getLong("sizeBytes"))
            assertEquals("", header.getString("sha256Checksum"))
            assertEquals("resume offset of file $index", offset.toLong(), header.getLong("resumeOffset"))
            val rest = file.second.copyOfRange(offset, file.second.size)
            assertArrayEquals(rest, peer.expectData(rest.size))
            val complete = peer.expectJson(FrameTag.FILE_COMPLETE)
            assertEquals(index, complete.getInt("fileIndex"))
            assertEquals(file.second.size.toLong(), complete.getLong("bytesWritten"))
            // Always the digest of the whole file, also when only the rest was sent.
            assertEquals(sha256(file.second), complete.getString("sha256Checksum"))
        }
        assertEquals("tx-test-session", String(peer.expect(FrameTag.TRANSFER_COMPLETE)))
    }

    // ── The four pairings of old and new peers ──────────────────────────────

    @Test fun newSenderToOldReceiverIsDfp1WithAnAckPerFile() {
        // An original Android receiver answers JSON without features; an original desktop answers text.
        for (accept in listOf(OLD_ACCEPT, "ACCEPTED")) {
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val sent = startSender(near, requestFor(mixed, BATCH_AND_RESUME), mixed)
                val request = peer.expectJson(FrameTag.TRANSFER_REQUEST)
                assertEquals(BATCH_AND_RESUME, request.features())
                assertEquals(mixed.size, request.getJSONArray("files").length())
                peer.write(FrameTag.TRANSFER_ACCEPT, accept)
                expectDfp1Stream(peer, mixed)
                assertFalse(sent.isDone)
                peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
                assertNull(sent.result())
            }
            near.close()
        }
    }

    @Test fun newSenderToNewReceiverStreamsWithoutPerFileAcks() {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(mixed, BATCH_AND_RESUME), mixed)
            assertEquals(BATCH_AND_RESUME, peer.expectJson(FrameTag.TRANSFER_REQUEST).features())
            // Android receivers grant batch only: no RESUME_STATE follows.
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            expectBatchStream(peer, mixed)
            // Success is reported only after the final acknowledgement; keep-alives are skipped.
            peer.assertQuiet()
            assertFalse(sent.isDone)
            peer.write(FrameTag.HEARTBEAT)
            peer.write(FrameTag.HEARTBEAT)
            peer.assertQuiet()
            assertFalse(sent.isDone)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    @Test fun oldSenderToNewReceiverNeverUsesFeaturesItDidNotOffer() {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(mixed), mixed)
            // Byte for byte the original request: no features field at all.
            assertNull(peer.expectJson(FrameTag.TRANSFER_REQUEST).features())
            // Even a receiver that wrongly grants them gets the original exchange.
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch","resume"]}""")
            expectDfp1Stream(peer, mixed)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    @Test fun oldSenderToOldReceiverIsUnchanged() {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(mixed), mixed)
            assertNull(peer.expectJson(FrameTag.TRANSFER_REQUEST).features())
            peer.write(FrameTag.TRANSFER_ACCEPT, OLD_ACCEPT)
            expectDfp1Stream(peer, mixed)
            // The original Android receiver's final status.
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    @Test fun resumeAloneGrantsNothing() {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(mixed, BATCH_AND_RESUME), mixed)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["resume"]}""")
            expectDfp1Stream(peer, mixed)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    // ── Batch details ───────────────────────────────────────────────────────

    @Test fun batchGroupsSmallFramesIntoFewSocketWrites() {
        val small: List<TestFile> = (0 until 60).map { "note-$it.txt" to randomBytes(200, it) }
        fun writesFor(accept: String, expectStream: (Peer) -> Unit): List<Int> {
            val (near, far) = socketPair()
            val recorded = RecordingOutput(near.getOutputStream())
            Peer(far).use { peer ->
                val sent = startSender(near, requestFor(small, BATCH_AND_RESUME), small, output = recorded)
                peer.expect(FrameTag.TRANSFER_REQUEST)
                peer.write(FrameTag.TRANSFER_ACCEPT, accept)
                expectStream(peer)
                peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
                assertNull(sent.result())
            }
            near.close()
            return recorded.writes.drop(1) // the request itself
        }

        val batch = writesFor("""{"status":"ACCEPTED","features":["batch"]}""") { expectBatchStream(it, small) }
        // 181 frames, about 30 KB in total: one write, flushed before waiting for the final reply.
        assertEquals(1, batch.size)
        assertTrue(batch.single() < 64 * 1024)

        // Without batch every frame is still its own write, as before.
        val original = writesFor(OLD_ACCEPT) { expectDfp1Stream(it, small) }
        assertEquals(60 * 3 + 1, original.size)
    }

    @Test fun batchWritesStayNearSixtyFourKilobytesForManySmallFiles() {
        val small: List<TestFile> = (0 until 400).map { "thumb-$it.png" to randomBytes(2_000, it) }
        val (near, far) = socketPair()
        val recorded = RecordingOutput(near.getOutputStream())
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(small, BATCH), small, output = recorded)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            expectBatchStream(peer, small)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
        val writes = recorded.writes.drop(1)
        assertTrue("expected grouped writes, got ${writes.size}", writes.size in 10..20)
        assertTrue(writes.dropLast(1).all { it in 64 * 1024 until 64 * 1024 + 4 * 1024 })
    }

    @Test fun recipientCancelOrErrorStopsTheStreamWithAClearMessage() {
        val cases = listOf(
            (FrameTag.TRANSFER_CANCEL to "") to "Transfer cancelled by recipient",
            (FrameTag.ERROR to "not enough storage space") to "The other device stopped the transfer: not enough storage space"
        )
        for ((notice, message) in cases) {
            val big: List<TestFile> = listOf("small.txt" to randomBytes(100, 1), "big.bin" to randomBytes(8 * 1024 * 1024, 2))
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                // The second file takes several seconds, so the notice arrives mid-stream.
                val sent = startSender(near, requestFor(big, BATCH), big, openFile = { SlowInput(big[it].second, 20) })
                peer.expect(FrameTag.TRANSFER_REQUEST)
                peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
                peer.expect(FrameTag.FILE_HEADER)
                val started = System.nanoTime()
                peer.write(FrameTag.HEARTBEAT)
                peer.write(notice.first, notice.second)
                assertEquals(message, sent.result())
                // Noticed by the periodic check, long before the stream would have ended.
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
            }
            near.close()
        }
    }

    @Test fun recipientNoticeIsSeenBetweenFilesWithoutAnyDataLoop() {
        // Empty files never enter the data loop; opening each one takes a while.
        val files: List<TestFile> = (0 until 400).map { "empty-$it" to ByteArray(0) }
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val opened = AtomicInteger()
            val sent = startSender(near, requestFor(files, BATCH), files, openFile = {
                opened.incrementAndGet()
                Thread.sleep(10)
                ByteArrayInputStream(ByteArray(0))
            })
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            Thread.sleep(300)
            peer.write(FrameTag.TRANSFER_CANCEL)
            assertEquals("Transfer cancelled by recipient", sent.result())
            assertTrue("opened ${opened.get()} files", opened.get() in 10..100)
        }
        near.close()
    }

    @Test fun recipientNoticeIsSeenWhileHashingAResumePrefix() {
        val files: List<TestFile> = listOf("slow.bin" to randomBytes(100 * 64 * 1024, 1))
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            // Hashing the prefix would take 5 seconds.
            val sent = startSender(near, requestFor(files, BATCH_AND_RESUME), files, openFile = { SlowInput(files[it].second, 50) })
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch","resume"]}""")
            peer.write(FrameTag.RESUME_STATE, """[{"i":0,"o":${files[0].second.size},"h":"${sha256(files[0].second)}"}]""")
            Thread.sleep(400)
            val started = System.nanoTime()
            peer.write(FrameTag.ERROR, "not enough storage space")
            assertEquals("The other device stopped the transfer: not enough storage space", sent.result())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        }
        near.close()
    }

    @Test fun recipientCancelOrErrorAtTheFinalAcknowledgementIsReported() {
        val files: List<TestFile> = listOf("a.txt" to randomBytes(100, 1))
        val cases = listOf(
            (FrameTag.TRANSFER_CANCEL to "tx-test-session") to "Transfer cancelled by recipient",
            (FrameTag.ERROR to "it could not save the files") to "The other device stopped the transfer: it could not save the files"
        )
        for ((notice, message) in cases) {
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val sent = startSender(near, requestFor(files, BATCH), files)
                peer.expect(FrameTag.TRANSFER_REQUEST)
                peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
                expectBatchStream(peer, files)
                peer.write(FrameTag.HEARTBEAT)
                peer.write(notice.first, notice.second)
                assertEquals(message, sent.result())
            }
            near.close()
        }
    }

    @Test fun batchRequiresTheFinalAckStatus() {
        val files: List<TestFile> = listOf("a.txt" to randomBytes(100, 1))
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH), files)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            expectBatchStream(peer, files)
            // A per-file style acknowledgement is not a completion in batch mode.
            peer.write(FrameTag.TRANSFER_ACK, FILE_ACK)
            assertEquals("Receiver did not confirm transfer completion: ACCEPTED", sent.result())
        }
        near.close()
    }

    @Test fun aSourceFileThatCannotBeReadIsReportedToBothSides() {
        val files: List<TestFile> = listOf("ok.txt" to randomBytes(100, 1), "gone.txt" to randomBytes(100, 2))
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH), files, openFile = { index ->
                if (index == 1) throw IOException("Could not open 'gone.txt'") else ByteArrayInputStream(files[index].second)
            })
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            // Frames still grouped for the next write are dropped; the reason arrives at a frame boundary.
            var last = peer.read()
            while (last.first != FrameTag.ERROR) last = peer.read()
            assertEquals("it could not read a file", String(last.second))
            // Not "connection lost": the connection was fine.
            assertEquals("Could not open 'gone.txt'", sent.result())
        }
        near.close()
    }

    @Test fun aSourceFileThatChangedSizeFailsInsteadOfSendingWrongData() {
        val files: List<TestFile> = listOf("grew.txt" to randomBytes(100, 1))
        for ((actual, message) in listOf(
            randomBytes(150, 1) to "'grew.txt' grew after it was selected",
            randomBytes(60, 1) to "Read 60 bytes for 'grew.txt', expected 100"
        )) {
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val sent = startSender(near, requestFor(files, BATCH), files, openFile = { ByteArrayInputStream(actual) })
                peer.expect(FrameTag.TRANSFER_REQUEST)
                peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
                assertEquals(message, sent.result())
                var last = peer.read()
                while (last.first != FrameTag.ERROR) last = peer.read()
                assertEquals("it could not read a file", String(last.second))
            }
            near.close()
        }
    }

    // ── Resume ──────────────────────────────────────────────────────────────

    private fun resumeRun(files: List<TestFile>, state: String, opens: AtomicInteger, offsets: Map<Int, Int>) {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH_AND_RESUME), files, openFile = {
                opens.incrementAndGet()
                ByteArrayInputStream(files[it].second)
            })
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch","resume"]}""")
            // Nothing is streamed before the resume state has been read.
            peer.assertQuiet()
            peer.write(FrameTag.RESUME_STATE, state)
            expectBatchStream(peer, files, offsets)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    @Test fun resumeSendsOnlyTheRestWhenThePrefixMatches() {
        val files: List<TestFile> = listOf(
            "done.bin" to randomBytes(50_000, 1),
            "half.bin" to randomBytes(900_000, 2),
            "fresh.bin" to randomBytes(1_000, 3)
        )
        val opens = AtomicInteger()
        val state = """[
            {"i":0,"o":50000,"h":"${sha256(files[0].second)}"},
            {"i":1,"o":400000,"h":"${sha256(files[1].second, 400_000).uppercase()}"}
        ]"""
        // The complete file sends no data at all; the half-done one sends the remaining 500 000 bytes.
        resumeRun(files, state, opens, mapOf(0 to 50_000, 1 to 400_000))
        assertEquals(3, opens.get())
    }

    @Test fun resumeSendsTheWholeFileWhenThePrefixDoesNotMatch() {
        val files: List<TestFile> = listOf("changed.bin" to randomBytes(600_000, 1), "other.bin" to randomBytes(2_000, 2))
        val opens = AtomicInteger()
        val state = """[{"i":0,"o":300000,"h":"${sha256(randomBytes(300_000, 99))}"}]"""
        resumeRun(files, state, opens, emptyMap())
        // The first file was opened once to hash its prefix and again to send it whole.
        assertEquals(3, opens.get())
    }

    @Test fun resumeIgnoresOffersItCannotCheck() {
        val files: List<TestFile> = listOf("a.bin" to randomBytes(10_000, 1), "b.bin" to randomBytes(10_000, 2))
        val opens = AtomicInteger()
        // More bytes than the file has, zero bytes, and a file that is not in the transfer.
        val state = """[
            {"i":0,"o":10001,"h":"${sha256(files[0].second)}"},
            {"i":1,"o":0,"h":"${sha256(ByteArray(0))}"},
            {"i":7,"o":5,"h":"00"}
        ]"""
        resumeRun(files, state, opens, emptyMap())
        assertEquals(2, opens.get())
    }

    @Test fun anEmptyResumeStateStreamsEverything() {
        resumeRun(mixed, "[]", AtomicInteger(), emptyMap())
    }

    @Test fun aLongPrefixHashKeepsTheConnectionAlive() {
        val files: List<TestFile> = listOf("slow.bin" to randomBytes(40 * 64 * 1024, 1))
        val held = 36 * 64 * 1024
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            // About 2.9 seconds to hash the prefix: at least one keep-alive must go out.
            val sent = startSender(near, requestFor(files, BATCH_AND_RESUME), files, openFile = { SlowInput(files[it].second, 80) })
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch","resume"]}""")
            peer.write(FrameTag.RESUME_STATE, """[{"i":0,"o":$held,"h":"${sha256(files[0].second, held)}"}]""")
            var heartbeats = 0
            var frame = peer.read()
            while (frame.first == FrameTag.HEARTBEAT) {
                heartbeats++
                assertEquals(0, frame.second.size)
                frame = peer.read()
            }
            assertTrue("expected a keep-alive during hashing", heartbeats >= 1)
            assertEquals(FrameTag.FILE_HEADER, frame.first)
            assertEquals(held.toLong(), JSONObject(String(frame.second)).getLong("resumeOffset"))
            assertArrayEquals(files[0].second.copyOfRange(held, files[0].second.size), peer.expectData(files[0].second.size - held))
            assertEquals(sha256(files[0].second), peer.expectJson(FrameTag.FILE_COMPLETE).getString("sha256Checksum"))
            peer.expect(FrameTag.TRANSFER_COMPLETE)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    @Test fun aMissingResumeStateIsAnError() {
        val files: List<TestFile> = listOf("a.bin" to randomBytes(10, 1))
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files, BATCH_AND_RESUME), files)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch","resume"]}""")
            peer.write(FrameTag.TRANSFER_ACK, FILE_ACK)
            assertEquals("Expected RESUME_STATE, received TRANSFER_ACK", sent.result())
        }
        near.close()
    }

    // ── Large manifests ─────────────────────────────────────────────────────

    private val many: List<TestFile> = (0 until 1500).map { "Photos/2026/holiday-picture-number-$it.jpg" to ByteArray(0) }

    @Test fun aLargeManifestGoesOutWithTheLargeTag() {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(many, BATCH_AND_RESUME), many)
            val request = peer.expectJson(FrameTag.TRANSFER_REQUEST_LARGE)
            assertEquals(1500, request.getJSONArray("files").length())
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            expectBatchStream(peer, many)
            peer.write(FrameTag.TRANSFER_ACK, FINAL_ACK)
            assertNull(sent.result())
        }
        near.close()
    }

    @Test fun aPeerThatPredatesLargeManifestsGetsAClearExplanation() {
        val (near, far) = socketPair()
        val sent = startSender(near, requestFor(many, BATCH_AND_RESUME), many)
        // An original peer reads the header, does not know tag 0x0C and drops the connection.
        val header = ByteArray(Protocol.HEADER_SIZE)
        FrameUtils.readPayloadInto(far.getInputStream(), header, header.size)
        assertEquals(0x0C.toByte(), header[5])
        far.close()
        assertEquals("The other device needs a newer version of DropFlow to receive this many files at once.", sent.result())
        near.close()
    }

    @Test fun aSmallRequestThatLosesTheConnectionIsNotBlamedOnTheVersion() {
        val files: List<TestFile> = listOf("a.bin" to randomBytes(10, 1))
        val (near, far) = socketPair()
        val sent = startSender(near, requestFor(files, BATCH_AND_RESUME), files)
        Peer(far).use { it.expect(FrameTag.TRANSFER_REQUEST) }
        assertEquals("Connection to recipient was lost", sent.result())
        near.close()
    }

    // ── Cancelling on this device ───────────────────────────────────────────

    private fun connect(): Pair<TransferConnection, Peer> {
        val (near, far) = socketPair()
        val session = TransferSession(peerUuid = "peer", peerName = "Peer", peerAddress = "127.0.0.1", peerPort = far.localPort,
            direction = SessionDirection.OUTGOING)
        return TransferConnection(near, session, onSessionStateChanged = {}) to Peer(far)
    }

    /** The same wiring as TransferEngine. */
    private fun startSender(connection: TransferConnection, request: TransferRequestPayload, openFile: (Int) -> InputStream) = inBackground {
        kotlinx.coroutines.runBlocking {
            FileSender().sendTransferSession(
                socketInputStream = checkNotNull(connection.inputStream),
                socketOutputStream = checkNotNull(connection.outputStream),
                requestPayload = request,
                openFile = openFile,
                progressTracker = trackerFor(request, SessionDirection.OUTGOING),
                setReadTimeout = { connection.readTimeoutMs = it },
                isCancelled = { connection.isCancelled },
                sendNotice = { tag, payload -> connection.sendNotice(tag, payload, drain = false) }
            ).also { connection.close() }
        }
    }

    /** Reads frames until the stream ends; fails if the framing is ever broken. */
    private fun framesUntilEnd(peer: Peer): List<FrameTag> {
        val tags = mutableListOf<FrameTag>()
        while (true) {
            val header = try {
                FrameUtils.readHeader(peer.input)
            } catch (_: java.io.EOFException) {
                return tags
            }
            FrameUtils.readPayload(peer.input, header.payloadLength)
            tags += header.tag
        }
    }

    @Test fun cancellingMidStreamSendsTransferCancelAtAFrameBoundary() {
        for (accept in listOf("""{"status":"ACCEPTED","features":["batch"]}""", OLD_ACCEPT)) {
            val files: List<TestFile> = listOf("big.bin" to randomBytes(8 * 1024 * 1024, 1))
            val (connection, peer) = connect()
            peer.use {
                val sent = startSender(connection, requestFor(files, BATCH_AND_RESUME)) { SlowInput(files[it].second, 20) }
                peer.expect(FrameTag.TRANSFER_REQUEST)
                peer.write(FrameTag.TRANSFER_ACCEPT, accept)
                peer.expect(FrameTag.FILE_HEADER)
                if (accept == OLD_ACCEPT) peer.write(FrameTag.TRANSFER_ACK, FILE_ACK)
                peer.expect(FrameTag.DATA_CHUNK)
                val started = System.nanoTime()
                connection.cancelTransfer()
                assertEquals("Transfer cancelled", sent.result())
                assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
                // Every frame before the cancel is whole, and the cancel is the last one.
                val tags = framesUntilEnd(peer)
                assertEquals(FrameTag.TRANSFER_CANCEL, tags.last())
                assertTrue(tags.dropLast(1).all { it == FrameTag.DATA_CHUNK })
            }
        }
    }

    @Test fun cancellingWhileTheRecipientDecidesSendsTransferCancel() {
        val files: List<TestFile> = listOf("a.bin" to randomBytes(10, 1))
        val (connection, peer) = connect()
        peer.use {
            val sent = startSender(connection, requestFor(files, BATCH_AND_RESUME)) { ByteArrayInputStream(files[it].second) }
            peer.expect(FrameTag.TRANSFER_REQUEST)
            // The sender is blocked reading the answer.
            peer.assertQuiet()
            val started = System.nanoTime()
            connection.cancelTransfer()
            assertEquals("Transfer cancelled", sent.result())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            assertEquals("tx-test-session", String(peer.expect(FrameTag.TRANSFER_CANCEL)))
            assertTrue(peer.atEnd())
        }
    }

    @Test fun aCancelThatCannotBeWrittenStillEndsWithinTheGracePeriod() {
        // The peer accepts and then stops reading, so the sender blocks in a write.
        val files: List<TestFile> = listOf("huge.bin" to ByteArray(64 * 1024 * 1024))
        val (connection, peer) = connect()
        peer.use {
            val sent = startSender(connection, requestFor(files, BATCH_AND_RESUME)) { ByteArrayInputStream(files[it].second) }
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, """{"status":"ACCEPTED","features":["batch"]}""")
            Thread.sleep(500)
            assertFalse(sent.isDone)
            val started = System.nanoTime()
            connection.cancelTransfer()
            assertEquals("Transfer cancelled", sent.result())
            val waited = (System.nanoTime() - started) / 1_000_000
            assertTrue("ended after $waited ms", waited in 1_000..3_000)
        }
    }

    @Test fun localCancelFlagIsCheckedBetweenFiles() {
        val files: List<TestFile> = (0 until 50).map { "f$it" to randomBytes(100, it) }
        val cancelled = AtomicBoolean(false)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val sent = startSender(near, requestFor(files), files, isCancelled = cancelled::get)
            peer.expect(FrameTag.TRANSFER_REQUEST)
            peer.write(FrameTag.TRANSFER_ACCEPT, OLD_ACCEPT)
            peer.expect(FrameTag.FILE_HEADER)
            cancelled.set(true)
            peer.write(FrameTag.TRANSFER_ACK, FILE_ACK)
            assertEquals("Transfer cancelled", sent.result())
            // The file in progress is finished or not started, never cut inside a frame.
            near.close()
            assertEquals(FrameTag.TRANSFER_CANCEL, framesUntilEnd(peer).last())
        }
    }
}
