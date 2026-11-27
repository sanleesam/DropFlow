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
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
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
    isCancelled: () -> Boolean = { false }
): Future<String?> = inBackground {
    runBlocking {
        FileSender().sendTransferSession(
            socketInputStream = socket.getInputStream(),
            socketOutputStream = output,
            requestPayload = request,
            openFile = openFile,
            progressTracker = trackerFor(request, SessionDirection.OUTGOING),
            setReadTimeout = { socket.soTimeout = it },
            isCancelled = isCancelled
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
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
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
