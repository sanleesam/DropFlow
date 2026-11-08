package com.dropflow.android.nearby

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.dropflow.android.R
import java.net.Inet4Address
import java.net.NetworkInterface

internal data class P2pEndpoint(val local: Inet4Address, val host: Inet4Address)

/** Native P2P client, separate from secondary STA. Only touches the requested lab group. */
@RequiresApi(29)
@SuppressLint("MissingPermission") // Activity requests the applicable Wi-Fi Direct permission before constructing this.
internal class NativeP2pLab(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val handler: Handler,
    private val ssid: String,
    private val onReady: (P2pEndpoint) -> Unit,
    private val reportFailure: (Int) -> Unit,
) {
    private var requested = false
    private var linked = false
    private var closed = false
    private val observe = object : Runnable {
        override fun run() {
            if (closed) return
            try {
                manager.requestGroupInfo(channel) { group ->
                    if (closed) return@requestGroupInfo
                    when {
                        group == null && linked -> reportFailure(R.string.nearby_lab_lost)
                        group != null && (group.networkName != ssid || group.isGroupOwner) ->
                            reportFailure(R.string.nearby_lab_wrong_network)
                        group != null && !linked -> inspect(group)
                    }
                }
                handler.postDelayed(this, 500)
            } catch (_: SecurityException) {
                reportFailure(R.string.nearby_lab_p2p_permission)
            }
        }
    }

    fun connect(passphrase: String) {
        try {
            manager.requestGroupInfo(channel) { group ->
                if (closed) return@requestGroupInfo
                if (group != null) {
                    reportFailure(R.string.nearby_lab_existing_p2p)
                    return@requestGroupInfo
                }
                try {
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(ssid)
                        .setPassphrase(passphrase)
                        .build()
                    requested = true
                    manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (!closed) handler.post(observe)
                        }

                        override fun onFailure(reason: Int) {
                            requested = false
                            if (!closed) reportFailure(R.string.nearby_lab_p2p_rejected)
                        }
                    })
                } catch (_: SecurityException) {
                    requested = false
                    reportFailure(R.string.nearby_lab_p2p_permission)
                } catch (_: IllegalArgumentException) {
                    requested = false
                    reportFailure(R.string.nearby_lab_p2p_rejected)
                }
            }
        } catch (_: SecurityException) {
            reportFailure(R.string.nearby_lab_p2p_permission)
        }
    }

    private fun inspect(group: WifiP2pGroup) {
        manager.requestConnectionInfo(channel) { info ->
            if (closed || linked || !info.groupFormed || info.isGroupOwner) return@requestConnectionInfo
            val host = info.groupOwnerAddress as? Inet4Address ?: return@requestConnectionInfo
            val name = group.`interface` ?: return@requestConnectionInfo
            val endpoint = runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                val p2p = interfaces.firstOrNull { it.name == name && it.isUp } ?: return@runCatching null
                val local = p2p.interfaceAddresses.firstOrNull { address ->
                    val ip = address.address as? Inet4Address ?: return@firstOrNull false
                    ip != host && ip.isSiteLocalAddress && samePrefix(ip.address, host.address, address.networkPrefixLength.toInt())
                }?.address as? Inet4Address ?: return@runCatching null
                val overlaps = interfaces.any { iface ->
                    iface.name != name && iface.isUp && iface.interfaceAddresses.any { other ->
                        samePrefix(other.address.address, host.address, other.networkPrefixLength.toInt())
                    }
                }
                if (overlaps) reportFailure(R.string.nearby_lab_route_conflict)
                if (host.isSiteLocalAddress && !overlaps) P2pEndpoint(local, host) else null
            }.getOrNull()
            if (endpoint != null) {
                linked = true
                onReady(endpoint)
            }
        }
    }

    fun close(onClosed: (Boolean) -> Unit) {
        if (closed) return
        closed = true
        handler.removeCallbacks(observe)
        val deadline = SystemClock.elapsedRealtime() + 3_000
        var released = false
        var removalRequested = false
        fun release(clean: Boolean) {
            if (released) return
            released = true
            runCatching { channel.close() }
            onClosed(clean)
        }
        if (!requested) {
            release(true)
            return
        }
        runCatching { manager.cancelConnect(channel, null) }
        fun removeOwnedGroup() {
            if (released) return
            val requestedInfo = runCatching {
                manager.requestGroupInfo(channel) { group ->
                    if (released) return@requestGroupInfo
                    when {
                        group?.networkName == ssid && SystemClock.elapsedRealtime() >= deadline -> release(false)
                        group?.networkName == ssid && !removalRequested -> {
                            removalRequested = true
                            if (runCatching {
                                manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() {
                                        handler.postDelayed(::removeOwnedGroup, 250)
                                    }
                                    override fun onFailure(reason: Int) = release(false)
                                })
                            }.isFailure) release(false)
                        }
                        group?.networkName == ssid -> handler.postDelayed(::removeOwnedGroup, 250)
                        group != null || linked || removalRequested || SystemClock.elapsedRealtime() >= deadline -> release(true)
                        else -> handler.postDelayed(::removeOwnedGroup, 250)
                    }
                }
            }
            if (requestedInfo.isFailure) release(false)
        }
        removeOwnedGroup()
        handler.postDelayed({ release(false) }, 3_500)
    }
}

/** Reject non-IPv4/invalid and unscoped prefixes; don't guess a default route. */
internal fun samePrefix(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
    if (a.size != 4 || b.size != 4 || prefix !in 1..32) return false
    for (bit in 0 until prefix) {
        val mask = 0x80 ushr (bit % 8)
        if (((a[bit / 8].toInt() xor b[bit / 8].toInt()) and mask) != 0) return false
    }
    return true
}
