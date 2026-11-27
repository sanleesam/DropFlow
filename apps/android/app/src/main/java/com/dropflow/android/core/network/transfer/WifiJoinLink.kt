package com.dropflow.android.core.network.transfer

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

/** Whether this phone can join a direct link without giving up its own Wi-Fi. */
object DirectLinkSupport {
    /**
     * What this phone offers: it never hosts, and it joins only when the
     * person turned the setting on, on Android 12 or newer, with a Wi-Fi chip
     * that holds a second connection next to the phone's own.
     */
    fun caps(enabled: Boolean, sdk: Int, keepsWifi: Boolean): DirectCaps =
        DirectCaps(hostRank = 0, join = enabled && sdk >= Build.VERSION_CODES.S && keepsWifi)

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
 * Joins the other device's private Wi-Fi network as a second connection,
 * next to the phone's own Wi-Fi. This phone never hosts.
 *
 * Only this app's sockets that ask for the new network use it: the process is
 * never bound to it, no network is saved or forgotten, and the request is
 * given back in [close]. If the phone's own Wi-Fi goes away while joining,
 * the join is abandoned.
 */
internal class WifiJoinLink(context: Context, private val enabled: Boolean = true) : DirectLink {
    private val appContext = context.applicationContext
    private val connectivity: ConnectivityManager? = appContext.getSystemService(ConnectivityManager::class.java)
    private val changed = Semaphore(0)

    // Written by the system's callback thread, read by the session thread.
    @Volatile private var network: Network? = null
    @Volatile private var properties: LinkProperties? = null
    @Volatile private var failure: String? = null

    // Session thread only, except [close].
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: ServerSocket? = null
    private var host: Inet4Address? = null
    private var closed = false

    override fun caps(): DirectCaps =
        DirectLinkSupport.caps(enabled, Build.VERSION.SDK_INT, DirectLinkSupport.keepsWifi(appContext))

    override fun host(tick: () -> Unit): HostedLink = throw DirectLinkException("this device cannot create a network")

    override fun join(offer: DirectOffer, tick: () -> Unit): JoinedLink {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !caps().join) {
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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun joinNetwork(offer: DirectOffer, tick: () -> Unit): JoinedLink {
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
        // The Wi-Fi this phone is on now. It has to survive the join.
        val own = ownWifi(manager)
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
                endpoint(joined, properties ?: manager.getLinkProperties(joined), offer)?.let { return it }
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
        val keep = synchronized(this) {
            if (!closed) listener = server
            !closed
        }
        if (!keep) {
            runCatching { server.close() }
            throw DirectLinkException("the direct link was closed")
        }
        return JoinedLink(address, server.localPort)
    }

    override fun accept(waitMs: Int): Socket? {
        val server = synchronized(this) { listener } ?: throw DirectLinkException("this device is not listening")
        return try {
            server.soTimeout = waitMs.coerceAtLeast(1)
            server.accept()
        } catch (_: SocketTimeoutException) {
            null
        } catch (error: IOException) {
            throw DirectLinkException("this device stopped listening on the direct link")
        }
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
