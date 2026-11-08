package com.dropflow.android.nearby

import android.app.Activity
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.dropflow.android.R
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Internal lab entry point. No data or network requests arrive via intents. */
class NearbyLinkLabActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private lateinit var ssid: EditText
    private lateinit var passphrase: EditText
    private lateinit var status: TextView
    private lateinit var run: Button
    private lateinit var cancel: Button
    private var session: Probe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        body.addView(TextView(this).apply {
            setText(R.string.nearby_lab_title)
            textSize = 24f
        })
        body.addView(TextView(this).apply { setText(R.string.nearby_lab_description) })
        ssid = EditText(this).apply {
            setHint(R.string.nearby_lab_ssid)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSaveEnabled = false
        }
        passphrase = EditText(this).apply {
            setHint(R.string.nearby_lab_passphrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        run = Button(this).apply {
            setText(R.string.nearby_lab_run)
            setOnClickListener { startProbe() }
        }
        cancel = Button(this).apply {
            setText(R.string.nearby_lab_cancel)
            isEnabled = false
            setOnClickListener { cancelProbe() }
        }
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        listOf(ssid, passphrase, run, cancel, status).forEach { body.addView(it) }
        setContentView(ScrollView(this).apply { addView(body) })
        refreshAvailability()
    }

    override fun onResume() {
        super.onResume()
        if (session == null && ::run.isInitialized && !run.isEnabled) refreshAvailability()
    }

    @StringRes
    private fun availability(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return R.string.nearby_lab_android_required
        val wifi = getSystemService(WifiManager::class.java)
        if (wifi?.isStaConcurrencyForLocalOnlyConnectionsSupported != true) {
            return R.string.nearby_lab_concurrency_required
        }
        return if (wifi.isWifiEnabled) R.string.nearby_lab_ready else R.string.nearby_lab_enable_wifi
    }

    private fun refreshAvailability() {
        val message = availability()
        run.isEnabled = message == R.string.nearby_lab_ready
        status.setText(message)
    }

    private fun startProbe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || session != null) return
        if (availability() != R.string.nearby_lab_ready) {
            refreshAvailability()
            return
        }
        val name = ssid.text.toString().trim()
        val password = passphrase.text.toString().trim()
        if (!Regex("DIRECT-DF-[0-9a-f]{10}").matches(name) || !Regex("[0-9a-f]{32}").matches(password)) {
            status.setText(R.string.nearby_lab_invalid_credentials)
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(
                WifiNetworkSpecifier.Builder().setSsid(name).setWpa2Passphrase(password).build(),
            )
            .build()
        passphrase.text.clear()
        val probe = Probe()
        session = probe
        run.isEnabled = false
        cancel.isEnabled = true
        status.setText(R.string.nearby_lab_waiting)
        probe.start(request)
    }

    override fun onDestroy() {
        cancelProbe()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun cancelProbe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            session?.finish(R.string.nearby_lab_cancelled)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class Probe {
        private val defaultNetwork = connectivity.activeNetwork
        private var selected: Network? = null
        private var properties: LinkProperties? = null
        private var isolated = false
        private var started = false
        private var registered = false
        private val expires = SystemClock.elapsedRealtime() + 110_000
        private val socketLock = Any()
        private var socket: Socket? = null
        @Volatile private var finished = false

        private val tick = object : Runnable {
            override fun run() {
                when {
                    finished -> return
                    !defaultUnchanged() -> finish(R.string.nearby_lab_primary_changed)
                    SystemClock.elapsedRealtime() >= expires -> finish(R.string.nearby_lab_no_link)
                    else -> handler.postDelayed(this, 250)
                }
            }
        }

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (finished) return
                if (selected != null && selected != network) {
                    finish(R.string.nearby_lab_wrong_network)
                    return
                }
                selected = network
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (finished || network != selected) return
                isolated = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (isolated) maybeExchange() else finish(R.string.nearby_lab_wrong_network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (finished || network != selected) return
                properties = linkProperties
                maybeExchange()
            }

            override fun onLost(network: Network) {
                if (network == selected) finish(R.string.nearby_lab_lost)
            }

            override fun onUnavailable() = finish(R.string.nearby_lab_no_link)
        }

        fun start(request: NetworkRequest) {
            try {
                connectivity.requestNetwork(request, callback, handler, 75_000)
                registered = true
                handler.post(tick)
            } catch (_: SecurityException) {
                finish(R.string.nearby_lab_no_link)
            } catch (_: IllegalArgumentException) {
                finish(R.string.nearby_lab_no_link)
            }
        }

        private fun defaultUnchanged(): Boolean {
            val current = connectivity.activeNetwork
            return current == defaultNetwork && (selected == null || current != selected)
        }

        private fun maybeExchange() {
            val network = selected ?: return
            if (finished || started || !isolated) return
            if (!defaultUnchanged()) {
                finish(R.string.nearby_lab_primary_changed)
                return
            }
            val link = properties ?: return
            val candidates = listOfNotNull(link.dhcpServerAddress) +
                link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }
            val host = candidates.firstOrNull { address ->
                address.isSiteLocalAddress &&
                    link.linkAddresses.none { it.address == address } &&
                    link.routes.any { route ->
                        !route.isDefaultRoute && !route.hasGateway() && route.destination.contains(address)
                    }
            } ?: return // Wait for DHCP; never resolve via DNS or use the default network.
            started = true
            status.setText(R.string.nearby_lab_exchanging)
            worker.execute {
                val result = try {
                    connect(network, host).use {
                        it.soTimeout = 5_000
                        NearbyLabWire.exchange(it.getInputStream(), it.getOutputStream())
                    }
                    R.string.nearby_lab_success
                } catch (_: IOException) {
                    R.string.nearby_lab_failed
                } catch (_: IllegalStateException) {
                    R.string.nearby_lab_failed
                } catch (_: SecurityException) {
                    R.string.nearby_lab_failed
                }
                handler.post {
                    finish(if (defaultUnchanged()) result else R.string.nearby_lab_primary_changed)
                }
            }
        }

        private fun connect(network: Network, host: Inet4Address): Socket {
            val stopAt = minOf(expires, SystemClock.elapsedRealtime() + 20_000)
            while (!finished && SystemClock.elapsedRealtime() < stopAt) {
                val candidate = synchronized(socketLock) {
                    if (finished) throw IOException("Probe closed")
                    network.socketFactory.createSocket().also { socket = it }
                }
                try {
                    candidate.connect(InetSocketAddress(host, NearbyLabWire.PORT), 1_200)
                    return candidate
                } catch (error: IOException) {
                    runCatching { candidate.close() }
                    if (finished) throw error
                    // The Windows group can offer DHCP before its scoped TCP listener is ready.
                    SystemClock.sleep(250)
                }
            }
            throw IOException("Windows lab listener did not become reachable")
        }

        fun finish(@StringRes message: Int) {
            if (finished) return
            finished = true
            handler.removeCallbacks(tick)
            synchronized(socketLock) {
                runCatching { socket?.close() }
                socket = null
            }
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            if (session === this) {
                session = null
                run.isEnabled = availability() == R.string.nearby_lab_ready
                cancel.isEnabled = false
                status.setText(message)
            }
        }
    }
}
