package com.dropflow.android.core.network.transfer

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "DropFlowDirectLink"

// Two devices on a slow shared network can agree to move the data onto a
// private Wi-Fi link between them (protocol section 7.5). The existing
// connection carries only the arrangement; if the link does not come up, the
// transfer stays where it is. The platform part (creating or joining the
// network) is behind DirectLink, so nothing here touches a radio.

/** The network a host created. */
class HostedLink(
    val ssid: String,
    val passphrase: String,
    /** Set when the host accepts the joiner's connection itself. */
    val listenPort: Int? = null
)

/** Where a joiner listens on the network it joined. Ignored when the host listens instead. */
class JoinedLink(val address: String, val port: Int)

/** The link could not be set up. The message goes to the other device, so it holds no local detail. */
class DirectLinkException(message: String) : Exception(message)

/**
 * The link failed after one device had left the shared network for it. There
 * is nothing to fall back to: the transfer ends like a lost connection.
 */
class DirectLinkLostException(reason: String) : IOException("The direct link could not be set up ($reason)")

/**
 * The platform side of a direct link. Every method may take seconds; the long
 * ones call `tick` at least once a second and pass on whatever it throws.
 * A failure is a [DirectLinkException] with a short reason.
 */
interface DirectLink {
    fun caps(): DirectCaps

    /** Creates the private network. */
    fun host(tick: () -> Unit): HostedLink

    /** Joins the network in [offer], without giving up the current one. */
    fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink

    /** Waits up to [waitMs] for the other device to connect on the private network. */
    fun accept(waitMs: Int): Socket?

    /** Connects over the private network: to [address], or to the network's gateway (the host) when it is null. */
    fun connect(address: String?, port: Int): Socket

    /** Releases the network. Safe to call more than once. */
    fun close()

    /**
     * True when this device can hold only one Wi-Fi connection, so joining
     * means leaving the shared network for the length of the transfer.
     */
    fun leavesNetwork(): Boolean = false

    /** For a device that leaves: starts listening on every interface before it joins, and returns the port. */
    fun listen(): Int = throw DirectLinkException("this device cannot listen before joining")

    /** For a host: true when [connectJoined] works on this platform. */
    fun acceptsLeaving(): Boolean = false

    /**
     * For a host: waits for a device to join the private network and connects
     * to it on [port], at the address the platform itself reports for it.
     */
    fun connectJoined(port: Int, tick: () -> Unit): Socket =
        throw DirectLinkException("this device cannot find a joiner by itself")
}

/** How long each step of the set-up may take, in milliseconds. Tests shorten them. */
data class LinkLimits(
    /** The joiner's wait for the host's offer. */
    val offerMs: Long = 25_000,
    /** The host's wait for the joiner; a phone may ask its owner first. */
    val joinMs: Long = 75_000,
    /** A listening joiner's wait for the host's connection; a Windows host needs time to find the phone. */
    val connectMs: Long = 30_000,
    val greetingMs: Int = 5_000,
    val pollMs: Int = 250
)

/** What a session needs to try a direct link. [adopt] makes the new socket the session's connection. */
class DirectOptions(
    val link: DirectLink,
    val adopt: (Socket) -> Pair<InputStream, OutputStream> = { it.getInputStream() to it.getOutputStream() },
    val limits: LinkLimits = LinkLimits()
)

/** Which side creates the network. The higher rank hosts; the other must be able to join. */
internal fun chooseHost(sender: DirectCaps, receiver: DirectCaps): String? {
    val senderCan = sender.hostRank > 0 && receiver.join
    val receiverCan = receiver.hostRank > 0 && sender.join
    return when {
        senderCan && receiverCan && sender.hostRank > receiver.hostRank -> DirectCaps.HOST_SENDER
        receiverCan -> DirectCaps.HOST_RECEIVER
        senderCan -> DirectCaps.HOST_SENDER
        else -> null
    }
}

/**
 * Why the offered network is not joined, or null when it may be. The name
 * comes from the other device, and this one is about to trust it with its
 * Wi-Fi: only a network that looks like a direct link is ever joined.
 */
internal fun offerProblem(offer: DirectOffer): String? {
    fun plain(text: String) = text.all { it.code in 0x21..0x7E }
    return when {
        !offer.ssid.startsWith("DIRECT-") || offer.ssid.length > 32 || !plain(offer.ssid) ->
            "the other device offered a network that is not a direct link"
        offer.passphrase.length !in 8..63 || !plain(offer.passphrase) ->
            "the other device offered an unusable network password"
        else -> null
    }
}

/**
 * Proves both ends of the new connection belong to this session: the side
 * that connected runs the usual handshake and shows the token. Throws when
 * the other end does not answer as expected.
 */
internal fun greet(socket: Socket, token: String, outgoing: Boolean, timeoutMs: Int) {
    socket.soTimeout = timeoutMs
    socket.tcpNoDelay = true
    // Unbuffered: the session's own reader takes over right after the last byte.
    val input = socket.getInputStream()
    val output = socket.getOutputStream()
    fun read(expected: FrameTag): ByteArray {
        val header = FrameUtils.readHeader(input)
        if (header.tag != expected) throw DirectLinkException("unexpected ${header.tag} on the direct link")
        return FrameUtils.readPayload(input, header.payloadLength)
    }
    val secret = token.toByteArray(StandardCharsets.UTF_8)
    if (outgoing) {
        FrameUtils.writeHeartbeat(output, Protocol.HELLO_HANDSHAKE)
        if (!read(FrameTag.HEARTBEAT).contentEquals(Protocol.HELLO_ACK.toByteArray(StandardCharsets.UTF_8))) {
            throw DirectLinkException("the direct link did not answer the handshake")
        }
        FrameUtils.writeFrame(output, FrameTag.DIRECT_RESULT, secret)
        if (!read(FrameTag.DIRECT_RESULT).contentEquals(LINK_OK)) {
            throw DirectLinkException("the other device did not confirm the direct link")
        }
    } else {
        if (!read(FrameTag.HEARTBEAT).contentEquals(Protocol.HELLO_HANDSHAKE.toByteArray(StandardCharsets.UTF_8))) {
            throw DirectLinkException("the direct link did not start with the handshake")
        }
        FrameUtils.writeHeartbeat(output, Protocol.HELLO_ACK)
        if (!MessageDigest.isEqual(read(FrameTag.DIRECT_RESULT), secret)) {
            throw DirectLinkException("an unknown device connected on the direct link")
        }
        FrameUtils.writeFrame(output, FrameTag.DIRECT_RESULT, LINK_OK)
    }
    socket.soTimeout = Protocol.READ_TIMEOUT_MS
}

private val LINK_OK = "OK".toByteArray(StandardCharsets.UTF_8)

/**
 * Tries to move a session onto a private link, after the accept and on the
 * shared connection. Sender and receiver run the same steps; only who hosts
 * differs.
 */
internal class LinkSetup(
    private val input: InputStream,
    private val output: FrameOutput,
    private val link: DirectLink,
    private val isCancelled: () -> Boolean,
    private val limits: LinkLimits,
    /** What a cancel or an error from the other device is reported as. */
    private val peerStopped: (FrameTag, ByteArray) -> PeerStoppedException
) {
    /** The set-up is over without a link. With a [reason] the other device still has to be told. */
    private class Settled(val reason: String?) : RuntimeException()

    /**
     * True once either device has left the shared network for the link: the
     * shared connection is finished and nothing more may be said on it.
     */
    @Volatile
    var left = false
        private set

    /**
     * The connected and greeted socket, or null when the transfer stays on
     * the shared connection. Throws only when that connection fails or the
     * transfer is cancelled. The link is released unless it is now in use.
     */
    fun run(iHost: Boolean): Socket? {
        var socket: Socket? = null
        try {
            socket = if (iHost) host() else join()
            return socket
        } finally {
            if (socket == null) runCatching { link.close() }
        }
    }

    private fun host(): Socket? {
        val hosted = try {
            link.host(::tick)
        } catch (error: Exception) {
            return gaveUp(error)
        }
        // 32 random hex characters.
        val token = UUID.randomUUID().toString().replace("-", "")
        val offer = DirectOffer(
            hosted.ssid, hosted.passphrase, token, hosted.listenPort,
            leavingOk = hosted.listenPort == null && link.acceptsLeaving()
        )
        send(FrameTag.DIRECT_OFFER, offer.toJsonObject().toString())

        if (hosted.listenPort != null) {
            return awaitConnection(token, limits.joinMs, "the other device did not join in time")
        }
        // The joiner listens and says where; this side connects out.
        val deadline = deadlineAfter(limits.joinMs)
        while (true) {
            keepAlive()
            if (System.nanoTime() >= deadline) return stay("the other device did not join in time")
            val (tag, payload) = next()
            return when (tag) {
                FrameTag.HEARTBEAT -> continue
                FrameTag.DIRECT_RESULT -> null
                FrameTag.DIRECT_JOINED -> {
                    val joined = DirectJoined.parse(payload) ?: return stay("the other device's address could not be read")
                    if (joined.leaving) findAndGreet(joined.port, token) else connectAndGreet(joined.address, joined.port, token)
                }
                else -> stay("unexpected $tag frame")
            }
        }
    }

    private fun join(): Socket? {
        val deadline = deadlineAfter(limits.offerMs)
        val offer: DirectOffer
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            if (System.nanoTime() >= deadline) return stay("the other device did not offer a link in time")
            val (tag, payload) = next()
            when (tag) {
                FrameTag.HEARTBEAT -> continue
                FrameTag.DIRECT_RESULT -> return null
                FrameTag.DIRECT_OFFER -> {
                    offer = DirectOffer.parse(payload) ?: return stay("the link offer could not be read")
                    break
                }
                else -> return stay("unexpected $tag frame")
            }
        }
        offerProblem(offer)?.let { return stay(it) }
        if (link.leavesNetwork()) return leaveAndJoin(offer)
        val joined = try {
            link.join(offer, ::tick)
        } catch (error: Exception) {
            return gaveUp(error)
        }
        val hostPort = offer.hostPort
        if (hostPort != null) return connectAndGreet(null, hostPort, offer.token)

        // A host that gave up in the meantime is already streaming or
        // receiving here: it must not hear from this set-up again.
        try {
            tick()
        } catch (error: Exception) {
            return gaveUp(error)
        }
        send(FrameTag.DIRECT_JOINED, DirectJoined(joined.address, joined.port).toJsonObject().toString())
        return awaitConnection(offer.token, limits.connectMs, "the other device did not connect in time")
    }

    /**
     * The joiner's side when it can hold only one Wi-Fi connection: it says
     * where it will listen, leaves the shared network, and the host finds it
     * on the private one. Until DIRECT_JOINED is sent it can still stay.
     */
    private fun leaveAndJoin(offer: DirectOffer): Socket? {
        if (!offer.leavingOk || offer.hostPort != null) {
            return stay("this device would have to leave the network, and the other device cannot work with that")
        }
        try {
            val port = link.listen()
            // A host that gave up meanwhile must not be left waiting for a device that never comes.
            tick()
            // The last frame on the shared connection, for both sides.
            send(FrameTag.DIRECT_JOINED, DirectJoined("", port, leaving = true).toJsonObject().toString())
        } catch (error: Exception) {
            return gaveUp(error)
        }
        left = true
        try {
            link.join(offer, ::cancelTick)
        } catch (error: Exception) {
            throw noWayBack(error)
        }
        val deadline = deadlineAfter(limits.connectMs)
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            if (System.nanoTime() >= deadline) throw DirectLinkLostException("the other device did not connect in time")
            val socket = try {
                link.accept(limits.pollMs)
            } catch (error: Exception) {
                throw noWayBack(error)
            } ?: continue
            try {
                greet(socket, offer.token, outgoing = false, timeoutMs = limits.greetingMs)
                return socket
            } catch (error: Exception) {
                // A stranger on the link is ignored; the real peer may still arrive.
                Log.i(TAG, "Ignored a connection on the direct link: ${error.message}")
                runCatching { socket.close() }
            }
        }
    }

    /** The host's side for a joiner that left: the shared connection is finished, whatever happens next. */
    private fun findAndGreet(port: Int, token: String): Socket {
        left = true
        val socket = try {
            link.connectJoined(port, ::cancelTick)
        } catch (error: Exception) {
            throw noWayBack(error)
        }
        try {
            greet(socket, token, outgoing = true, timeoutMs = limits.greetingMs)
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw noWayBack(error)
        }
    }

    /**
     * After one side has left the shared network there is nothing to fall
     * back to: the transfer ends, and a retry continues it where resume applies.
     */
    private fun noWayBack(error: Exception): Exception = when {
        error is TransferCancelledException || isCancelled() -> TransferCancelledException()
        error is DirectLinkLostException -> error
        error is DirectLinkException -> DirectLinkLostException(error.message ?: "the link could not be set up")
        error is SocketTimeoutException -> DirectLinkLostException("the direct link did not answer in time")
        else -> {
            Log.w(TAG, "Direct link failed after leaving", error)
            DirectLinkLostException("the link could not be set up")
        }
    }

    /** For work that happens after the shared connection is gone: only a cancel can stop it. */
    private fun cancelTick() {
        if (isCancelled()) throw TransferCancelledException()
    }

    /**
     * Waits for the other device to connect on the private network, while
     * watching the shared connection for "I gave up".
     */
    private fun awaitConnection(token: String, patienceMs: Long, late: String): Socket? {
        val deadline = deadlineAfter(patienceMs)
        while (true) {
            keepAlive()
            if (System.nanoTime() >= deadline) return stay(late)
            when (val tag = pending()?.first) {
                null, FrameTag.HEARTBEAT -> Unit
                FrameTag.DIRECT_RESULT -> return null
                else -> return stay("unexpected $tag frame")
            }
            val socket = try {
                link.accept(limits.pollMs)
            } catch (error: Exception) {
                return gaveUp(error)
            } ?: continue
            try {
                greet(socket, token, outgoing = false, timeoutMs = limits.greetingMs)
                return socket
            } catch (error: Exception) {
                // A stranger on the link is ignored; the real peer may still arrive.
                Log.i(TAG, "Ignored a connection on the direct link: ${error.message}")
                runCatching { socket.close() }
            }
        }
    }

    private fun connectAndGreet(address: String?, port: Int, token: String): Socket? {
        val socket = try {
            link.connect(address, port)
        } catch (error: Exception) {
            return gaveUp(error)
        }
        return try {
            greet(socket, token, outgoing = true, timeoutMs = limits.greetingMs)
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            gaveUp(error)
        }
    }

    /** Gives up on the link, tells the other side, and stays on this connection. */
    private fun stay(reason: String): Socket? {
        if (isCancelled()) throw TransferCancelledException()
        Log.i(TAG, "Staying on the shared network: $reason")
        send(FrameTag.DIRECT_RESULT, "FAILED: $reason".filterNot { it.isISOControl() }.take(200))
        return null
    }

    /**
     * The platform could not do its part, or its `tick` stopped it. A cancel
     * still ends the transfer, and so does a dead shared connection: telling
     * the other device then fails.
     */
    private fun gaveUp(error: Exception): Socket? = when (error) {
        is TransferCancelledException, is PeerStoppedException -> throw error
        is Settled -> error.reason?.let(::stay)
        is DirectLinkException -> stay(error.message ?: "the link could not be set up")
        is SocketTimeoutException -> stay("the direct link did not answer in time")
        else -> {
            Log.w(TAG, "Direct link failed", error)
            stay("the link could not be set up")
        }
    }

    /**
     * Runs while the platform works on the link: keeps the shared connection
     * alive and notices when the other device cancels or gives up meanwhile.
     */
    private fun tick() {
        keepAlive()
        while (true) {
            val (tag, _) = pending() ?: return
            when (tag) {
                FrameTag.HEARTBEAT -> Unit
                FrameTag.DIRECT_RESULT -> throw Settled(null)
                else -> throw Settled("unexpected $tag frame")
            }
        }
    }

    private fun keepAlive() {
        if (isCancelled()) throw TransferCancelledException()
        if (System.nanoTime() - output.lastWriteNanos >= Protocol.HEARTBEAT_INTERVAL_MS * 1_000_000) {
            FrameUtils.writeFrame(output, FrameTag.HEARTBEAT, ByteArray(0))
        }
    }

    /** One frame from the other side on the shared connection, if any has arrived. */
    private fun pending(): Pair<FrameTag, ByteArray>? = if (input.available() > 0) next() else null

    private fun next(): Pair<FrameTag, ByteArray> {
        val header = FrameUtils.readHeader(input)
        val payload = FrameUtils.readPayload(input, header.payloadLength)
        if (header.tag == FrameTag.TRANSFER_CANCEL || header.tag == FrameTag.ERROR) throw peerStopped(header.tag, payload)
        return header.tag to payload
    }

    private fun send(tag: FrameTag, text: String) =
        FrameUtils.writeFrame(output, tag, text.toByteArray(StandardCharsets.UTF_8))

    private fun deadlineAfter(millis: Long) = System.nanoTime() + millis * 1_000_000
}
