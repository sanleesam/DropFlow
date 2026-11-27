package com.dropflow.android.core.network.transfer

import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Drives FileReceiver with a scripted sender over a loopback socket, storing into a temporary folder. */
class FileReceiverTest {
    @get:Rule val folder = TemporaryFolder()

    private val mixed: List<TestFile> = listOf(
        "notes.txt" to randomBytes(10, 1),
        "Photos/photo.jpg" to randomBytes(300_000, 2),
        "empty.bin" to ByteArray(0),
        "video.mp4" to randomBytes(700_000, 3)
    )

    private class Receive(val receiver: FileReceiver, val done: Future<Boolean>)

    private fun startReceiver(
        socket: Socket,
        request: TransferRequestPayload,
        storage: FakeStorage,
        input: InputStream = socket.getInputStream(),
        isCancelled: () -> Boolean = { false }
    ): Receive {
        socket.soTimeout = 15_000
        val receiver = FileReceiver(storage)
        return Receive(receiver, inBackground {
            runBlocking {
                receiver.receiveTransferSession(input, socket.getOutputStream(), request,
                    trackerFor(request, SessionDirection.INCOMING), isCancelled)
            }
        })
    }

    private fun Peer.sendFile(index: Int, file: TestFile, digest: String = sha256(file.second), chunk: Int = 256 * 1024) {
        write(FrameTag.FILE_HEADER, FileHeaderPayload(index, file.first, file.second.size.toLong()).toJsonObject().toString())
        sendBody(index, file, digest, chunk)
    }

    private fun Peer.sendBody(index: Int, file: TestFile, digest: String = sha256(file.second), chunk: Int = 256 * 1024) {
        var sent = 0
        while (sent < file.second.size) {
            val count = minOf(chunk, file.second.size - sent)
            FrameUtils.writeFrame(output, FrameTag.DATA_CHUNK, file.second.copyOfRange(sent, sent + count))
            sent += count
        }
        write(FrameTag.FILE_COMPLETE, FileCompletePayload(index, file.second.size.toLong(), digest).toJsonObject().toString())
    }

    private fun published(files: List<TestFile>) = files.associate { it.first.substringAfterLast('/') to it.second }

    private fun assertSameFiles(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual[name]) }
    }

    // ── Negotiation ─────────────────────────────────────────────────────────

    @Test fun anOriginalSenderGetsTheOriginalExchange() {
        val storage = FakeStorage(folder.root)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed), storage)
            // Exactly the acceptance an earlier Android release sent.
            val accept = peer.expectJson(FrameTag.TRANSFER_ACCEPT)
            assertEquals(setOf("status", "resumeOffset"), accept.keySet())
            assertEquals("ACCEPTED", accept.getString("status"))
            for ((index, file) in mixed.withIndex()) {
                peer.write(FrameTag.FILE_HEADER, FileHeaderPayload(index, file.first, file.second.size.toLong()).toJsonObject().toString())
                // Every file header is acknowledged before its data.
                val ack = peer.expectJson(FrameTag.TRANSFER_ACK)
                assertEquals("ACCEPTED", ack.getString("status"))
                assertEquals(0L, ack.getLong("resumeOffset"))
                peer.sendBody(index, file)
                peer.assertQuiet(50)
            }
            assertTrue(storage.publishedFiles().isEmpty())
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            assertEquals("ACK", peer.expectJson(FrameTag.TRANSFER_ACK).getString("status"))
            assertTrue(run.done.result())
        }
        near.close()
        assertSameFiles(published(mixed), storage.publishedFiles())
    }

    @Test fun aBatchSenderGetsNoRepliesUntilTheFinalAcknowledgement() {
        val storage = FakeStorage(folder.root)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed, BATCH_AND_RESUME), storage)
            // Batch is granted; resume is not, although it was offered.
            val accept = peer.expectJson(FrameTag.TRANSFER_ACCEPT)
            assertEquals("ACCEPTED", accept.getString("status"))
            assertEquals("""["batch"]""", accept.getJSONArray("features").toString())

            // Keep-alives may sit between any two frames.
            peer.write(FrameTag.HEARTBEAT)
            for ((index, file) in mixed.withIndex()) {
                peer.sendFile(index, file, chunk = 100_000)
                peer.write(FrameTag.HEARTBEAT, "still here")
            }
            // No per-file acknowledgement, no resume state, nothing published yet.
            peer.assertQuiet(400)
            assertTrue(storage.publishedFiles().isEmpty())
            assertFalse(run.done.isDone)

            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            assertEquals("ACK", peer.expectJson(FrameTag.TRANSFER_ACK).getString("status"))
            // The acknowledgement comes after every file was published.
            assertSameFiles(published(mixed), storage.publishedFiles())
            assertTrue(run.done.result())
        }
        near.close()
        assertTrue(storage.staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun keepAlivesAndErrorsAreNotPartOfTheOriginalExchange() {
        val storage = FakeStorage(folder.root)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.write(FrameTag.HEARTBEAT)
            assertFalse(run.done.result())
            assertEquals("Expected FILE_HEADER frame tag, received 0x0A", run.receiver.failureReason)
            // No notice either: an original sender does not expect one.
            assertTrue(peer.atEnd())
        }
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    // ── Stopping ────────────────────────────────────────────────────────────

    @Test fun aSenderErrorEndsABatchSessionWithItsReason() {
        val storage = FakeStorage(folder.root)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed, BATCH), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.sendFile(0, mixed[0])
            peer.write(FrameTag.ERROR, "it could not read a file")
            assertFalse(run.done.result())
            assertEquals("The sending device stopped the transfer: it could not read a file", run.receiver.failureReason)
            // Nothing is sent back to a peer that already stopped.
            assertTrue(peer.atEnd())
        }
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun aSenderCancelEndsEitherKindOfSession() {
        for (features in listOf(BATCH, emptyList())) {
            val storage = FakeStorage(folder.newFolder())
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val run = startReceiver(near, requestFor(mixed, features), storage)
                peer.expect(FrameTag.TRANSFER_ACCEPT)
                peer.write(FrameTag.TRANSFER_CANCEL, "tx-test-session")
                assertFalse(run.done.result())
                assertEquals("Transfer cancelled by sender", run.receiver.failureReason)
            }
            near.close()
            assertEquals(emptyList<String>(), storage.leftovers())
        }
    }

    @Test fun batchRequiresTheSendersDigest() {
        for (digest in listOf("", "0".repeat(64))) {
            val storage = FakeStorage(folder.newFolder())
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val run = startReceiver(near, requestFor(mixed, BATCH), storage)
                peer.expect(FrameTag.TRANSFER_ACCEPT)
                peer.sendFile(0, mixed[0])
                peer.sendFile(1, mixed[1], digest = digest)
                assertFalse(run.done.result())
                assertEquals("SHA-256 checksum verification failed", run.receiver.failureReason)
                // The sender is told why, without details of this device.
                assertEquals("the data did not match the transfer", String(peer.expect(FrameTag.ERROR)))
            }
            near.close()
            // The file that did verify is removed too: all or nothing.
            assertEquals(emptyList<String>(), storage.leftovers())
        }
    }

    @Test fun aFullDiskIsReportedAsNotEnoughStorageSpace() {
        val storage = FakeStorage(folder.root).apply { failWritesOf = "photo.jpg" }
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed, BATCH), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.sendFile(0, mixed[0])
            peer.sendFile(1, mixed[1])
            assertFalse(run.done.result())
            assertEquals("Not enough storage space on this device", run.receiver.failureReason)
            assertEquals("not enough storage space", String(peer.expect(FrameTag.ERROR)))
        }
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun otherStorageFailuresAreReportedWithoutLocalPaths() {
        // Closing happens in the background; the failure must still stop the session.
        val storage = FakeStorage(folder.root).apply { failCloseOf = "notes.txt" }
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed, BATCH), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            mixed.forEachIndexed { index, file -> runCatching { peer.sendFile(index, file) } }
            runCatching { peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session") }
            assertFalse(run.done.result())
            assertEquals("close failed: EIO (I/O error)", run.receiver.failureReason)
            assertEquals("it could not save the files", String(peer.expect(FrameTag.ERROR)))
        }
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun aFailedPublishRemovesEverythingIncludingFilesAlreadyPublished() {
        val storage = FakeStorage(folder.root, publishesInAnyOrder = false).apply { failPublishOf = "video.mp4" }
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(mixed, BATCH), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            mixed.forEachIndexed { index, file -> peer.sendFile(index, file) }
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            assertFalse(run.done.result())
            assertEquals("it could not save the files", String(peer.expect(FrameTag.ERROR)))
        }
        near.close()
        assertTrue(storage.events.contains("publish:notes.txt"))
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun aLocalCancelInBatchModeSendsTransferCancel() {
        val storage = FakeStorage(folder.root)
        val cancelled = AtomicBoolean(false)
        val (near, far) = socketPair()
        // The same input wrapper the app uses: a blocked read wakes up for the cancel.
        near.soTimeout = SessionInput.SLICE_MS
        val input = SessionInput(near.getInputStream(), cancelled::get)
        Peer(far).use { peer ->
            val receiver = FileReceiver(storage)
            val request = requestFor(mixed, BATCH)
            val done = inBackground {
                runBlocking {
                    receiver.receiveTransferSession(input, near.getOutputStream(), request,
                        trackerFor(request, SessionDirection.INCOMING), cancelled::get)
                }
            }
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.sendFile(0, mixed[0])
            peer.write(FrameTag.FILE_HEADER, FileHeaderPayload(1, mixed[1].first, mixed[1].second.size.toLong()).toJsonObject().toString())
            peer.assertQuiet()
            val started = System.nanoTime()
            cancelled.set(true)
            assertEquals("tx-test-session", String(peer.expect(FrameTag.TRANSFER_CANCEL)))
            assertFalse(done.result())
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            assertEquals("Transfer cancelled", receiver.failureReason)
        }
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun aLostConnectionSendsNoNoticeAndLeavesNothing() {
        val storage = FakeStorage(folder.root)
        val (near, far) = socketPair()
        val run = startReceiver(near, requestFor(mixed, BATCH), storage)
        Peer(far).use { peer ->
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.sendFile(0, mixed[0])
            peer.write(FrameTag.FILE_HEADER, FileHeaderPayload(1, mixed[1].first, mixed[1].second.size.toLong()).toJsonObject().toString())
            FrameUtils.writeFrame(peer.output, FrameTag.DATA_CHUNK, mixed[1].second.copyOf(1000))
        }
        assertFalse(run.done.result())
        near.close()
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun headersThatDoNotMatchTheApprovedTransferAreRejected() {
        val wrong = listOf(
            FileHeaderPayload(0, "other.txt", 10),
            FileHeaderPayload(0, "notes.txt", 11),
            FileHeaderPayload(1, "notes.txt", 10),
            // Resume was not granted, so an offset is never acceptable.
            FileHeaderPayload(0, "notes.txt", 10, resumeOffset = 5)
        )
        for (header in wrong) {
            val storage = FakeStorage(folder.newFolder())
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val run = startReceiver(near, requestFor(mixed, BATCH_AND_RESUME), storage)
                peer.expect(FrameTag.TRANSFER_ACCEPT)
                peer.write(FrameTag.FILE_HEADER, header.toJsonObject().toString())
                assertFalse(run.done.result())
                assertEquals("File header does not match the accepted transfer", run.receiver.failureReason)
                assertEquals("the data did not match the transfer", String(peer.expect(FrameTag.ERROR)))
            }
            near.close()
            assertEquals(emptyList<String>(), storage.leftovers())
        }
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    /** Sends every file at once, then counts the keep-alives that arrive before the final ACK. */
    private fun keepAlivesBeforeFinalAck(files: List<TestFile>, features: List<String>, storage: FakeStorage): Int {
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(files, features), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            for ((index, file) in files.withIndex()) {
                peer.write(FrameTag.FILE_HEADER, FileHeaderPayload(index, file.first, file.second.size.toLong()).toJsonObject().toString())
                if (features.isEmpty()) peer.expect(FrameTag.TRANSFER_ACK)
                peer.sendBody(index, file)
            }
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            var keepAlives = 0
            var frame = peer.read()
            while (frame.first == FrameTag.HEARTBEAT) {
                assertEquals(0, frame.second.size)
                keepAlives++
                frame = peer.read()
            }
            assertEquals(FrameTag.TRANSFER_ACK, frame.first)
            assertEquals("ACK", JSONObject(String(frame.second)).getString("status"))
            assertTrue(run.done.result())
            near.close()
            assertSameFiles(published(files), storage.publishedFiles())
            return keepAlives
        }
    }

    @Test fun slowPublishingSendsKeepAlivesInBatchMode() {
        // 600 quick publishes, three at a time: about 3 seconds before the final ACK.
        val files: List<TestFile> = (0 until 600).map { "f$it.bin" to randomBytes(20, it) }
        val storage = FakeStorage(folder.root).apply { publishDelayMillis = 15 }
        val keepAlives = keepAlivesBeforeFinalAck(files, BATCH, storage)
        assertTrue("keep-alives: $keepAlives", keepAlives in 1..3)
    }

    @Test fun slowStorageDuringTheStreamSendsKeepAlivesInBatchMode() {
        // The sender has written everything and waits, while targets take 3 seconds to create.
        val files: List<TestFile> = (0 until 30).map { "f$it.bin" to randomBytes(20, it) }
        val storage = FakeStorage(folder.root).apply { prepareDelayMillis = 300 }
        val keepAlives = keepAlivesBeforeFinalAck(files, BATCH, storage)
        assertTrue("keep-alives: $keepAlives", keepAlives in 1..3)
    }

    @Test fun theOriginalExchangeNeverGetsKeepAlives() {
        val files: List<TestFile> = (0 until 6).map { "f$it.bin" to randomBytes(20, it) }
        // In-order publishing, 2.7 seconds in total.
        val storage = FakeStorage(folder.root, publishesInAnyOrder = false).apply { publishDelayMillis = 450 }
        assertEquals(0, keepAlivesBeforeFinalAck(files, emptyList(), storage))
    }

    @Test fun savingIsNotInterruptedByACancelOnceEveryFileIsVerified() {
        val files: List<TestFile> = (0 until 60).map { "f$it.bin" to randomBytes(20, it) }
        for (ordered in listOf(false, true)) {
            val storage = FakeStorage(folder.newFolder(), publishesInAnyOrder = !ordered).apply { publishDelayMillis = if (ordered) 10 else 30 }
            val cancelled = AtomicBoolean(false)
            val (near, far) = socketPair()
            Peer(far).use { peer ->
                val run = startReceiver(near, requestFor(files, BATCH), storage, isCancelled = cancelled::get)
                peer.expect(FrameTag.TRANSFER_ACCEPT)
                files.forEachIndexed { index, file -> peer.sendFile(index, file) }
                peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
                while (storage.events.none { it.startsWith("publish:") }) Thread.sleep(5)
                // Too late: stopping now would leave the transfer half saved.
                cancelled.set(true)
                assertEquals("ACK", peer.expectJson(FrameTag.TRANSFER_ACK).getString("status"))
                assertTrue(run.done.result())
            }
            near.close()
            assertSameFiles(published(files), storage.publishedFiles())
            assertFalse(storage.events.any { it.startsWith("cleanup:") })
        }
    }

    @Test fun aCancelJustBeforeSavingStillStopsEverything() {
        val files: List<TestFile> = (0 until 20).map { "f$it.bin" to randomBytes(20, it) }
        // Closing a file takes a while, so the cancel lands while the files are being synced.
        val storage = FakeStorage(folder.root).apply { closeDelayMillis = 100 }
        val cancelled = AtomicBoolean(false)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(files, BATCH), storage, isCancelled = cancelled::get)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            files.forEachIndexed { index, file -> peer.sendFile(index, file) }
            cancelled.set(true)
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            assertEquals(FrameTag.TRANSFER_CANCEL, peer.read().first)
            assertFalse(run.done.result())
        }
        near.close()
        assertFalse(storage.events.any { it.startsWith("publish:") })
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun savingFinishesWhenTheSenderGoesAwayAfterEverythingVerified() {
        // About 3 seconds of saving, so a keep-alive is attempted on the dead connection too.
        val files: List<TestFile> = (0 until 100).map { "f$it.bin" to randomBytes(20, it) }
        val storage = FakeStorage(folder.root).apply { publishDelayMillis = 90 }
        val (near, far) = socketPair()
        val run = startReceiver(near, requestFor(files, BATCH), storage)
        Peer(far).use { peer ->
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            files.forEachIndexed { index, file -> peer.sendFile(index, file) }
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            while (storage.events.none { it.startsWith("publish:") }) Thread.sleep(5)
        }
        // The confirmation could not be sent, but nothing is missing on this device.
        assertTrue(run.done.result())
        near.close()
        assertSameFiles(published(files), storage.publishedFiles())
        assertFalse(storage.events.any { it.startsWith("cleanup:") })
    }

    @Test fun targetsArePreparedAheadButOnlyAFewAtATime() {
        val files: List<TestFile> = (0 until 300).map { "f$it.bin" to randomBytes(50, it) }
        val storage = FakeStorage(folder.root)
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(files, BATCH), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            peer.sendFile(0, files[0])
            // With one file received, the next ones are already being prepared, but not all 300.
            Thread.sleep(500)
            val prepared = storage.events.count { it.startsWith("prepare:") }
            assertTrue("prepared $prepared", prepared in 2..40)
            for (index in 1 until files.size) peer.sendFile(index, files[index])
            peer.write(FrameTag.TRANSFER_COMPLETE, "tx-test-session")
            peer.expect(FrameTag.TRANSFER_ACK)
            assertTrue(run.done.result())
        }
        near.close()
        assertTrue("look-ahead ${storage.mostPreparedAhead.get()}", storage.mostPreparedAhead.get() <= 40)
        assertSameFiles(published(files), storage.publishedFiles())
        // Prepared in manifest order per name, published only after the last file arrived.
        val firstPublish = storage.events.indexOfFirst { it.startsWith("publish:") }
        assertEquals(300, storage.events.take(firstPublish).count { it.startsWith("prepare:") })
    }

    @Test fun aFailureCleansUpTargetsThatWerePreparedAhead() {
        val files: List<TestFile> = (0 until 100).map { "f$it.bin" to randomBytes(50, it) }
        val storage = FakeStorage(folder.root).apply { prepareDelayMillis = 20 }
        val (near, far) = socketPair()
        Peer(far).use { peer ->
            val run = startReceiver(near, requestFor(files, BATCH), storage)
            peer.expect(FrameTag.TRANSFER_ACCEPT)
            for (index in 0 until 5) peer.sendFile(index, files[index])
            peer.write(FrameTag.ERROR, "it could not read a file")
            assertFalse(run.done.result())
        }
        near.close()
        // Give a prepare that was in flight time to remove its own target.
        Thread.sleep(300)
        val prepared = storage.events.count { it.startsWith("prepare:") }
        assertTrue("prepared $prepared", prepared in 5..40)
        assertEquals(prepared, storage.events.count { it.startsWith("cleanup:") })
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun filesWithTheSameNameAreAllocatedInManifestOrder() {
        val storage = FakeStorage(folder.root).apply { prepareDelayMillis = 5 }
        val paths = (0 until 40).map { if (it % 2 == 0) "same.txt" else "Same.TXT" } + (0 until 20).map { "other-$it.txt" }
        val pipeline = ReceivePipeline(storage, paths)
        val targets = paths.indices.map { index ->
            pipeline.take(index) {}.also { target ->
                target.stream.write(index)
                pipeline.finish(index) {}
            }
        }
        pipeline.awaitDurable {}
        // Staging numbers are handed out in the order prepare was called for each name.
        val sameNumbers = targets.take(40).map { it.stagingFile!!.name.removeSuffix(".part").toInt() }
        assertEquals(sameNumbers.sorted(), sameNumbers)
        pipeline.publishAll {}
        pipeline.close()
        assertEquals(60, storage.publishedFiles().size)
        // Each published file holds the byte written for its own index.
        targets.forEachIndexed { index, target -> assertArrayEquals(byteArrayOf(index.toByte()), target.publishedFile!!.readBytes()) }
    }

    @Test fun nothingIsPublishedBeforeEveryFileIsSynced() {
        val storage = FakeStorage(folder.root).apply { failCloseOf = "f2" }
        val paths = (0 until 5).map { "f$it" }
        val pipeline = ReceivePipeline(storage, paths)
        paths.indices.forEach { index ->
            // A background failure may surface at the next call; it must surface by awaitDurable.
            runCatching {
                pipeline.take(index) {}.stream.write(1)
                pipeline.finish(index) {}
            }
        }
        val error = assertThrows(LocalFailureException::class.java) { pipeline.awaitDurable {} }
        assertEquals("it could not save the files", error.notice)
        pipeline.discard()
        assertFalse(storage.events.any { it.startsWith("publish:") })
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    @Test fun waitingForStorageCanBeInterruptedByACancel() {
        val storage = FakeStorage(folder.root).apply { prepareDelayMillis = 5_000 }
        val pipeline = ReceivePipeline(storage, listOf("slow.bin"))
        val started = System.nanoTime()
        assertThrows(TransferCancelledException::class.java) { pipeline.take(0) { throw TransferCancelledException() } }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
        inBackground { pipeline.discard() }
        Thread.sleep(5_500)
        // The prepare that was still running removed its own target when it finished.
        assertEquals(emptyList<String>(), storage.leftovers())
    }

    // ── Notices on a real connection ────────────────────────────────────────

    private fun connect(): Pair<TransferConnection, Peer> {
        val (near, far) = socketPair()
        val session = TransferSession(peerUuid = "peer", peerName = "Peer", peerAddress = "127.0.0.1", peerPort = far.localPort,
            direction = SessionDirection.INCOMING)
        return TransferConnection(near, session, onSessionStateChanged = {}) to Peer(far)
    }

    @Test fun aNoticeSurvivesUnreadInputAndWaitsForThePeerToClose() {
        val (connection, peer) = connect()
        // The sender is still streaming when the receiver gives up.
        val flood = inBackground {
            runCatching { repeat(200) { FrameUtils.writeFrame(peer.output, FrameTag.DATA_CHUNK, ByteArray(64 * 1024)) } }
        }
        Thread.sleep(200)
        val started = System.nanoTime()
        val closing = inBackground {
            connection.sendNotice(FrameTag.ERROR, "not enough storage space".toByteArray(), drain = true).also { connection.close() }
        }
        // The notice arrives whole, followed by the end of the stream, despite megabytes of unread input.
        assertEquals("not enough storage space", String(peer.expect(FrameTag.ERROR)))
        assertTrue(peer.atEnd())
        flood.result()
        peer.close()
        assertTrue(closing.result())
        // Returned when the peer closed, not after the full wait.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_400)
    }

    @Test fun drainingGivesUpAfterTheGracePeriod() {
        val (connection, peer) = connect()
        peer.use {
            val started = System.nanoTime()
            assertTrue(connection.sendNotice(FrameTag.TRANSFER_CANCEL, ByteArray(0), drain = true))
            val waited = (System.nanoTime() - started) / 1_000_000
            assertTrue("waited $waited ms", waited in 1_400..2_200)
            connection.close()
            assertEquals(0, peer.expect(FrameTag.TRANSFER_CANCEL).size)
        }
    }

    @Test fun noNoticeIsWrittenAfterAFailedWrite() {
        val broken = object : java.io.OutputStream() {
            var writes = 0
            override fun write(value: Int) = throw IOException("Broken pipe")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes++
                throw IOException("Broken pipe")
            }
        }
        val output = FrameOutput(broken)
        assertThrows(IOException::class.java) { FrameUtils.writeFrame(output, FrameTag.DATA_CHUNK, ByteArray(10)) }
        assertTrue(output.broken)
        // A frame may be half written: anything more would land inside it.
        assertFalse(output.notice(FrameTag.ERROR, "x".toByteArray()))
        assertEquals(1, broken.writes)
    }
}
