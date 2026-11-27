package com.dropflow.android.core.network.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals

internal typealias TestFile = Pair<String, ByteArray>

internal val BATCH = listOf(Protocol.FEATURE_BATCH)
internal val BATCH_AND_RESUME = listOf(Protocol.FEATURE_BATCH, Protocol.FEATURE_RESUME)

internal const val OLD_ACCEPT = """{"status":"ACCEPTED","resumeOffset":0}"""
internal const val FILE_ACK = """{"status":"ACCEPTED","resumeOffset":0}"""
internal const val FINAL_ACK = """{"status":"ACK","resumeOffset":0}"""

internal fun randomBytes(size: Int, seed: Int): ByteArray = ByteArray(size).also { Random(seed.toLong()).nextBytes(it) }

internal fun sha256(bytes: ByteArray, length: Int = bytes.size): String =
    MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, length) }.digest().toHex()

internal fun requestFor(files: List<TestFile>, features: List<String> = emptyList()) = TransferRequestPayload(
    sessionId = "tx-test-session",
    senderId = "sender-id",
    senderName = "Sender",
    senderPlatform = "Android",
    totalFiles = files.size,
    totalSizeBytes = files.sumOf { it.second.size.toLong() },
    files = files.mapIndexed { index, file -> FileMetadataPayload(index, file.first, file.second.size.toLong()) },
    features = features
)

internal fun trackerFor(request: TransferRequestPayload, direction: SessionDirection) =
    TransferProgressTracker(request.sessionId, request.totalFiles, request.totalSizeBytes, direction) {}

/** Two connected loopback sockets. */
internal fun socketPair(): Pair<Socket, Socket> {
    val loopback = InetAddress.getLoopbackAddress()
    ServerSocket(0, 1, loopback).use { server ->
        val near = Socket(loopback, server.localPort)
        val far = server.accept()
        near.tcpNoDelay = true
        far.tcpNoDelay = true
        return near to far
    }
}

private val background = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }

internal fun <T> inBackground(block: () -> T): Future<T> = background.submit(Callable { block() })

internal fun <T> Future<T>.result(seconds: Long = 20): T = get(seconds, TimeUnit.SECONDS)

/** The other end of the wire, driven step by step by a test. */
internal class Peer(val socket: Socket) : Closeable {
    val input: InputStream = socket.getInputStream()
    val output: OutputStream = socket.getOutputStream()

    init {
        socket.soTimeout = 15_000
    }

    fun read(): Pair<FrameTag, ByteArray> {
        val header = FrameUtils.readHeader(input)
        return header.tag to FrameUtils.readPayload(input, header.payloadLength)
    }

    fun expect(tag: FrameTag): ByteArray {
        val (actual, payload) = read()
        assertEquals(tag, actual)
        return payload
    }

    fun expectJson(tag: FrameTag) = JSONObject(String(expect(tag), Charsets.UTF_8))

    /** The next frame that is not a keep-alive, which must be [tag]. */
    fun await(tag: FrameTag): ByteArray {
        while (true) {
            val (actual, payload) = read()
            if (actual == FrameTag.HEARTBEAT) continue
            assertEquals(tag, actual)
            return payload
        }
    }

    /** Reads DATA_CHUNK frames until [size] bytes have arrived. */
    fun expectData(size: Int): ByteArray {
        val data = ByteArrayOutputStream()
        while (data.size() < size) data.write(expect(FrameTag.DATA_CHUNK))
        assertEquals(size, data.size())
        return data.toByteArray()
    }

    fun write(tag: FrameTag, payload: String = "") = FrameUtils.writeFrame(output, tag, payload.toByteArray(Charsets.UTF_8))

    /** Nothing arrives for a while: the other side is waiting for this one. */
    fun assertQuiet(millis: Long = 200) {
        Thread.sleep(millis)
        assertEquals("the other side sent data without waiting", 0, input.available())
    }

    /** True when the other side has closed its write side with nothing more to read. */
    fun atEnd(): Boolean = try {
        input.read() == -1
    } catch (_: IOException) {
        true
    }

    override fun close() = socket.close()
}

/** Runs FileSender on a socket the way TransferEngine does, without a TransferConnection. */
internal fun startSender(
    socket: Socket,
    request: TransferRequestPayload,
    files: List<TestFile>,
    output: OutputStream = socket.getOutputStream(),
    openFile: (Int) -> InputStream = { ByteArrayInputStream(files[it].second) },
    isCancelled: () -> Boolean = { false },
    direct: DirectOptions? = null
): Future<String?> = inBackground {
    runBlocking {
        FileSender().sendTransferSession(
            socketInputStream = socket.getInputStream(),
            socketOutputStream = output,
            requestPayload = request,
            openFile = openFile,
            progressTracker = trackerFor(request, SessionDirection.OUTGOING),
            setReadTimeout = { socket.soTimeout = it },
            isCancelled = isCancelled,
            direct = direct
        )
    }
}

/** An input stream that takes a while, to keep a transfer in flight. */
internal class SlowInput(private val bytes: ByteArray, private val pauseMillis: Long, private val step: Int = 64 * 1024) : InputStream() {
    private var position = 0
    override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        Thread.sleep(pauseMillis)
        val count = minOf(length, step, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}

/** Records the size of every write that reaches the socket. */
internal class RecordingOutput(private val raw: OutputStream) : OutputStream() {
    val writes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        writes += length
        raw.write(buffer, offset, length)
    }
    override fun flush() = raw.flush()
}

/** Storage in a temporary folder. Staged files are invisible until published. */
internal class FakeStorage(root: File, override val publishesInAnyOrder: Boolean = true) : ReceiveStorage {
    val staging = File(root, "staging").apply { mkdirs() }
    val published = File(root, "published").apply { mkdirs() }
    // Tests iterate this while worker threads add to it; a synchronized list
    // only guards single calls, so iteration needs a copy-on-write list.
    val events: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    private val created = AtomicInteger()
    private val unused = AtomicInteger()
    /** The most targets that were prepared but not yet written to or closed. */
    val mostPreparedAhead = AtomicInteger()

    @Volatile var prepareDelayMillis = 0L
    @Volatile var publishDelayMillis = 0L
    @Volatile var closeDelayMillis = 0L
    /** Makes writes to the file with this name fail with [writeError]. */
    @Volatile var failWritesOf: String? = null
    @Volatile var writeError: IOException = IOException("write failed: ENOSPC (No space left on device)")
    /** Makes closing the file with this name fail. */
    @Volatile var failCloseOf: String? = null
    @Volatile var failPublishOf: String? = null

    override fun prepareTargetStream(fileName: String): WritableTarget {
        Thread.sleep(prepareDelayMillis)
        val number = created.getAndIncrement()
        val stage = File(staging, "$number.part")
        val name = fileName.substringAfterLast('/')
        val stream = object : FileOutputStream(stage) {
            private var waiting = true
            private fun reached() {
                if (waiting) {
                    waiting = false
                    unused.decrementAndGet()
                }
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                reached()
                if (name == failWritesOf) throw writeError
                super.write(buffer, offset, length)
            }
            override fun close() {
                reached()
                Thread.sleep(closeDelayMillis)
                super.close()
                if (name == failCloseOf) throw IOException("close failed: EIO (I/O error)")
            }
        }
        mostPreparedAhead.accumulateAndGet(unused.incrementAndGet(), ::maxOf)
        events += "prepare:$fileName"
        return WritableTarget(stream, stage, null, name)
    }

    override fun completePendingFile(target: WritableTarget) {
        Thread.sleep(publishDelayMillis)
        if (target.displayName == failPublishOf) throw IOException("Could not publish '${target.displayName}'")
        val stage = checkNotNull(target.stagingFile)
        synchronized(this) {
            var final = File(published, target.displayName)
            var copy = 1
            while (final.exists()) final = File(published, "${target.displayName} (${copy++})")
            check(stage.renameTo(final))
            target.publishedFile = final
        }
        events += "publish:${target.displayName}"
    }

    override fun cleanupTarget(target: WritableTarget) {
        runCatching { target.stream.close() }
        target.stagingFile?.delete()
        target.publishedFile?.delete()
        events += "cleanup:${target.displayName}"
    }

    fun publishedFiles(): Map<String, ByteArray> = published.listFiles().orEmpty().associate { it.name to it.readBytes() }
    fun leftovers(): List<String> = (staging.listFiles().orEmpty().toList() + published.listFiles().orEmpty().toList()).map { it.name }
}

/**
 * A "private network" that is really loopback: it exercises everything about
 * a direct link except the radio.
 */
internal open class LoopbackLink(hostRank: Int, join: Boolean) : DirectLink {
    private val caps = DirectCaps(hostRank, join)
    /** As host: accept the joiner's connection (otherwise the joiner listens). */
    @Volatile var hostListens = false
    @Volatile var failHost = false
    @Volatile var failJoin = false
    /** One Wi-Fi connection only: joining means leaving the shared network. */
    @Volatile var leaves = false
    /** As host: able to find a joiner that left ([connectJoined]). */
    @Volatile var findsJoiner = true
    val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val closed = AtomicBoolean(false)
    /** The network a joiner expects to be offered; null accepts any. */
    protected open val expectedNetwork: Pair<String, String>? = SSID to PASSPHRASE
    @Volatile private var listener: ServerSocket? = null

    protected fun open(): Int = ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { listener = it }.localPort

    /** Where this link listens, once it does. */
    val listeningPort: Int? get() = listener?.localPort

    override fun caps() = caps

    override fun host(tick: () -> Unit): HostedLink {
        calls += "host"
        tick()
        if (failHost) throw DirectLinkException("this adapter cannot create a network")
        return HostedLink(SSID, PASSPHRASE, if (hostListens) open() else null)
    }

    override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
        calls += "join"
        tick()
        expectedNetwork?.let { assertEquals(it, offer.ssid to offer.passphrase) }
        if (failJoin) throw DirectLinkException("could not join the network")
        // A device that leaves is listening already.
        val port = listener?.localPort ?: if (offer.hostPort == null) open() else 0
        return JoinedLink("127.0.0.1", port)
    }

    override fun leavesNetwork() = leaves

    override fun listen(): Int {
        calls += "listen"
        return open()
    }

    override fun acceptsLeaving() = findsJoiner

    override fun connectJoined(port: Int, tick: () -> Unit): Socket {
        calls += "find"
        tick()
        return Socket("127.0.0.1", port)
    }

    override fun accept(waitMs: Int): Socket? {
        val server = listener ?: throw DirectLinkException("not listening")
        server.soTimeout = waitMs
        return try {
            server.accept()
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun connect(address: String?, port: Int): Socket {
        calls += "connect"
        return Socket(address ?: "127.0.0.1", port)
    }

    override fun close() {
        calls += "close"
        runCatching { listener?.close() }
        closed.set(true)
    }

    companion object {
        const val SSID = "DIRECT-DF-test"
        const val PASSPHRASE = "0123456789abcdef"
    }
}

/** Batch mode as the receiving side sees it: every file whole and in order, then the end of the session. */
internal fun Peer.expectBatchFiles(files: List<TestFile>) {
    for ((index, file) in files.withIndex()) {
        val header = JSONObject(String(await(FrameTag.FILE_HEADER), Charsets.UTF_8))
        assertEquals(index, header.getInt("fileIndex"))
        assertEquals(file.first, header.getString("relativePath"))
        org.junit.Assert.assertArrayEquals(file.first, file.second, expectData(file.second.size))
        val complete = expectJson(FrameTag.FILE_COMPLETE)
        assertEquals(sha256(file.second), complete.getString("sha256Checksum"))
    }
    assertEquals("tx-test-session", String(expect(FrameTag.TRANSFER_COMPLETE)))
}

/** Batch mode as the sending side writes it, without the final TRANSFER_COMPLETE. */
internal fun Peer.sendBatchFiles(files: List<TestFile>, chunk: Int = 256 * 1024) {
    for ((index, file) in files.withIndex()) {
        write(FrameTag.FILE_HEADER, FileHeaderPayload(index, file.first, file.second.size.toLong()).toJsonObject().toString())
        var sent = 0
        while (sent < file.second.size) {
            val count = minOf(chunk, file.second.size - sent)
            FrameUtils.writeFrame(output, FrameTag.DATA_CHUNK, file.second.copyOfRange(sent, sent + count))
            sent += count
        }
        write(FrameTag.FILE_COMPLETE, FileCompletePayload(index, file.second.size.toLong(), sha256(file.second)).toJsonObject().toString())
    }
}
