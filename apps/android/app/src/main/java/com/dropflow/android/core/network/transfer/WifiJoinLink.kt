package com.dropflow.android.core.network.transfer

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "DropFlowWifiJoinLink"

/** How this phone can take part in a direct link. */
enum class LinkMode {
    /** Android 9 or older: an app cannot ask for a named Wi-Fi network. */
    UNAVAILABLE,

    /** A second Wi-Fi connection, next to the phone's own. */
    SECOND_CONNECTION,

    /** One Wi-Fi connection: the phone leaves its own Wi-Fi while a transfer runs, then returns. */
    LEAVES_WIFI
}

/** What this phone can do for a direct link, and what the person allowed. */
object DirectLinkSupport {
    fun mode(sdk: Int, keepsWifi: Boolean): LinkMode = when {
        sdk >= Build.VERSION_CODES.S && keepsWifi -> LinkMode.SECOND_CONNECTION
        sdk >= Build.VERSION_CODES.Q -> LinkMode.LEAVES_WIFI
        else -> LinkMode.UNAVAILABLE
    }

    fun mode(context: Context): LinkMode = mode(Build.VERSION.SDK_INT, keepsWifi(context))

    /**
     * What this phone offers: it never hosts, and it joins only in the way
     * the person turned on. Leaving Wi-Fi has its own consent: having allowed
     * a second connection never means the phone may leave its Wi-Fi.
     */
    fun caps(mode: LinkMode, secondConnection: Boolean, leavingWifi: Boolean): DirectCaps = DirectCaps(
        hostRank = 0,
        join = when (mode) {
            LinkMode.SECOND_CONNECTION -> secondConnection
            LinkMode.LEAVES_WIFI -> leavingWifi
            LinkMode.UNAVAILABLE -> false
        }
    )

    /** True when joining leaves the phone's own Wi-Fi connected. */
    fun keepsWifi(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.isStaConcurrencyForLocalOnlyConnectionsSupported == true
        }.getOrDefault(false)
    }
}

/**
 * The host of a private network, as seen from a device that joined it: the
 * first candidate (DHCP server, then gateways) that is a private IPv4
 * address, is not this device, and is directly reachable on the link.
 */
internal fun pickLinkHost(
    candidates: List<InetAddress>,
    mine: List<InetAddress>,
    onLink: (InetAddress) -> Boolean
): Inet4Address? = candidates.filterIsInstance<Inet4Address>()
    .firstOrNull { it.isSiteLocalAddress && it !in mine && onLink(it) }

/**
 * Joins the other device's private Wi-Fi network. This phone never hosts.
 *
 * With a second Wi-Fi connection the phone's own Wi-Fi stays up, and the
 * join is abandoned if it goes away meanwhile. A phone with one connection
 * leaves its Wi-Fi for the length of the transfer, which the person allowed
 * separately; it listens before it joins, because the host has to find it.
 *
 * Either way only this app's sockets use the new network: the process is
 * never bound to it, no network is saved or forgotten, and the request is
 * given back in [close], after which the phone returns to its own Wi-Fi.
 */
internal class WifiJoinLink(
    context: Context,
    private val secondConnection: Boolean,
    private val leavingWifi: Boolean
) : DirectLink {
    private val appContext = context.applicationContext
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val changed = Semaphore(0)
    private val mode: LinkMode by lazy { DirectLinkSupport.mode(appContext) }

    // Written by the system's callback thread, read by the session thread.
    @Volatile private var network: Network? = null
    @Volatile private var properties: LinkProperties? = null
    @Volatile private var failure: String? = null

    // Session thread only, except [close].
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: ServerSocket? = null
    private var host: Inet4Address? = null
    private var closed = false

    override fun caps(): DirectCaps = DirectLinkSupport.caps(mode, secondConnection, leavingWifi)

    override fun leavesNetwork(): Boolean = mode == LinkMode.LEAVES_WIFI

    override fun host(tick: () -> Unit): HostedLink = throw DirectLinkException("this device cannot create a network")

    /**
     * Before leaving Wi-Fi: listens on every interface, since the address on
     * the new network is not known yet. There is no way back after this, so
     * what is known to fail is refused here, while the transfer can still
     * stay where it is.
     */
    override fun listen(): Int {
        if (!leavesNetwork() || !caps().join) throw DirectLinkException("this device cannot join a direct link")
        val wifiOn = runCatching { appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
        if (!wifiOn) throw DirectLinkException("Wi-Fi is off on this device")
        // Android only connects an app that is in front (or runs its transfer service).
        if (!inFront()) throw DirectLinkException("DropFlow is not open on this device")
        val server = ServerSocket()
        try {
            // Room for a stray connection from the old network without shutting the host out.
            server.bind(InetSocketAddress(0), 4)
        } catch (error: IOException) {
            runCatching { server.close() }
            Log.w(TAG, "Could not listen before joining", error)
            throw DirectLinkException("this device could not listen for the direct link")
        }
        keep(server)
        return server.localPort
    }

    private fun inFront(): Boolean = runCatching {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }.getOrDefault(true)

    private fun keep(server: ServerSocket) {
        val kept = synchronized(this) {
            if (!closed) listener = server
            !closed
        }
        if (!kept) {
            runCatching { server.close() }
            throw DirectLinkException("the direct link was closed")
        }
    }

    override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !caps().join) {
            throw DirectLinkException("this device cannot join a direct link")
        }
        try {
            return joinNetwork(offer, tick)
        } catch (error: Throwable) {
            // Nothing stays requested after a failed or interrupted join.
            close()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun joinNetwork(offer: DirectOffer, tick: () -> Unit): JoinedLink {
        val leaving = leavesNetwork()
        val manager = connectivity ?: throw DirectLinkException("this device cannot join a direct link")
        val request = try {
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // A local network: it must not become the phone's route to the internet.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(
                    WifiNetworkSpecifier.Builder().setSsid(offer.ssid).setWpa2Passphrase(offer.passphrase).build()
                )
                .build()
        } catch (error: RuntimeException) {
            throw DirectLinkException("the offered network cannot be joined")
        }
        // The Wi-Fi this phone is on now. With a second connection it has to
        // survive the join; a phone that leaves is expected to lose it.
        val own = if (leaving) emptyList() else ownWifi(manager)
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(found: Network) {
                if (network == null) network = found else if (network != found) failure = "the direct link changed"
                changed.release()
            }

            override fun onLinkPropertiesChanged(found: Network, linkProperties: LinkProperties) {
                if (found == network) properties = linkProperties
                changed.release()
            }

            override fun onLost(lost: Network) {
                if (lost == network) failure = "the direct link was lost"
                changed.release()
            }

            // The person declined, the network was not found, or the wait ran out.
            override fun onUnavailable() {
                failure = "the network was not joined"
                changed.release()
            }
        }
        synchronized(this) {
            if (closed) throw DirectLinkException("the direct link was closed")
            try {
                // The system may ask the person to approve the network the first time.
                // A phone with one connection drops its own Wi-Fi once this is granted.
                manager.requestNetwork(request, watcher, JOIN_WAIT_MS)
            } catch (error: RuntimeException) {
                Log.w(TAG, "The network request was refused", error)
                throw DirectLinkException("this device could not ask for the network")
            }
            callback = watcher
        }

        val deadline = SystemClock.elapsedRealtime() + JOIN_WAIT_MS
        while (true) {
            tick()
            failure?.let { throw DirectLinkException(it) }
            if (own.any { manager.getNetworkCapabilities(it) == null }) {
                throw DirectLinkException("this device's own Wi-Fi changed")
            }
            val joined = network
            if (joined != null) {
                if (joined in own) throw DirectLinkException("this device's own Wi-Fi would be replaced")
                val link = properties ?: manager.getLinkProperties(joined)
                if (leaving) {
                    // Already listening on every interface; the host finds this phone by itself.
                    val server = synchronized(this) { listener } ?: throw DirectLinkException("this device is not listening")
                    val address = link?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull()
                    return JoinedLink(address?.hostAddress.orEmpty(), server.localPort)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) endpoint(joined, link, offer)?.let { return it }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw DirectLinkException(
                    if (joined == null) "the network was not joined in time" else "the network gave this device no address"
                )
            }
            // Wakes early for a callback; tick runs at least four times a second.
            changed.tryAcquire(POLL_MS, TimeUnit.MILLISECONDS)
            changed.drainPermits()
        }
    }

    /** Wi-Fi networks the phone is connected to, without VPNs that merely run over them. */
    @Suppress("DEPRECATION") // A one-time snapshot; a callback would only add state to clean up.
    private fun ownWifi(manager: ConnectivityManager): List<Network> = manager.allNetworks.filter { candidate ->
        val capabilities = manager.getNetworkCapabilities(candidate)
        capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Where this phone is on the joined network, once it has an address
     * there. Null while DHCP is still running.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun endpoint(joined: Network, link: LinkProperties?, offer: DirectOffer): JoinedLink? {
        link ?: return null
        val addresses = link.linkAddresses.map { it.address }
        val mine = addresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        val address = mine.hostAddress ?: return null
        if (offer.hostPort != null) {
            // The host listens: it is the network's DHCP server or gateway. Never resolved by name.
            val candidates = listOfNotNull(link.dhcpServerAddress) +
                link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway }
            host = pickLinkHost(candidates, addresses) { candidate ->
                link.routes.any { !it.isDefaultRoute && !it.hasGateway() && it.destination.contains(candidate) }
            } ?: return null
            return JoinedLink(address, 0)
        }
        // The host connects to this phone: listen on the new network's address only.
        val server = ServerSocket()
        try {
            server.bind(InetSocketAddress(mine, 0), 1)
        } catch (error: IOException) {
            runCatching { server.close() }
            Log.w(TAG, "Could not listen on the direct link", error)
            throw DirectLinkException("this device could not listen on the direct link")
        }
        keep(server)
        return JoinedLink(address, server.localPort)
    }

    override fun accept(waitMs: Int): Socket? {
        val server = synchronized(this) { listener } ?: throw DirectLinkException("this device is not listening")
        val socket = try {
            server.soTimeout = waitMs.coerceAtLeast(1)
            server.accept()
        } catch (_: SocketTimeoutException) {
            return null
        } catch (error: IOException) {
            throw DirectLinkException("this device stopped listening on the direct link")
        }
        if (arrivedOnLink(socket)) return socket
        Log.i(TAG, "Ignored a connection that did not arrive on the direct link")
        runCatching { socket.close() }
        return null
    }

    /**
     * A phone that leaves listens on every interface, so a connection counts
     * only when it reached this phone at an address of the joined network.
     */
    private fun arrivedOnLink(socket: Socket): Boolean {
        if (!leavesNetwork()) return true
        val joined = network ?: return false
        val mine = runCatching { connectivity?.getLinkProperties(joined)?.linkAddresses?.map { it.address } }.getOrNull()
        // Not known (the system would not say): the token check still applies.
        if (mine.isNullOrEmpty()) return true
        return socket.localAddress in mine
    }

    override fun connect(address: String?, port: Int): Socket {
        // Only a host connects to an address it was told; this phone never hosts.
        if (address != null) throw DirectLinkException("this device cannot create a network")
        val joined = network ?: throw DirectLinkException("the network is not joined")
        val target = host ?: throw DirectLinkException("the host's address is not known")
        val stopAt = SystemClock.elapsedRealtime() + CONNECT_WAIT_MS
        while (true) {
            // Bound to the joined network; every other socket keeps using the phone's own.
            val socket = joined.socketFactory.createSocket()
            try {
                socket.connect(InetSocketAddress(target, port), CONNECT_ATTEMPT_MS)
                return socket
            } catch (error: IOException) {
                runCatching { socket.close() }
                // The host's listener may come up a moment after its DHCP server.
                if (synchronized(this) { closed } || SystemClock.elapsedRealtime() >= stopAt) {
                    throw DirectLinkException("the host could not be reached on the direct link")
                }
                SystemClock.sleep(CONNECT_PAUSE_MS)
            }
        }
    }

    override fun close() {
        val (watcher, server) = synchronized(this) {
            closed = true
            val held = callback to listener
            callback = null
            listener = null
            held
        }
        runCatching { server?.close() }
        // Gives the network back: the system disconnects it, and nothing was saved.
        if (watcher != null) runCatching { connectivity?.unregisterNetworkCallback(watcher) }
    }

    private companion object {
        /** The system may show its approval sheet; the host waits 75 seconds in all. */
        const val JOIN_WAIT_MS = 60_000
        const val POLL_MS = 250L
        const val CONNECT_WAIT_MS = 5_000L
        const val CONNECT_ATTEMPT_MS = 1_500
        const val CONNECT_PAUSE_MS = 250L
    }
}
