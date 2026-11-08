package com.dropflow.android.nearby

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
    private lateinit var run: Switch
    private lateinit var direct: Switch
    private var updatingSwitch = false
    private var destroying = false
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
        direct = Switch(this).apply {
            setText(R.string.nearby_lab_p2p_mode)
            isChecked = !supportsSecondary()
            isEnabled = supportsSecondary()
            visibility = if (supportsNativeP2p()) View.VISIBLE else View.GONE
        }
        run = Switch(this).apply {
            setText(R.string.nearby_lab_run)
            setOnCheckedChangeListener { _, enabled ->
                if (!updatingSwitch) {
                    if (enabled) {
                        startProbe()
                        if (session == null) setRunning(false)
                    } else {
                        session?.finish(R.string.nearby_lab_cancelled)
                    }
                }
            }
        }
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        listOf(ssid, passphrase, direct, run, status).forEach { body.addView(it) }
        setContentView(ScrollView(this).apply { addView(body) })
        direct.setOnCheckedChangeListener { _, _ -> if (session == null) refreshAvailability() }
        refreshAvailability()
    }

    override fun onResume() {
        super.onResume()
        if (session == null && ::run.isInitialized && !run.isEnabled) refreshAvailability()
    }

    @StringRes
    private fun availability(): Int {
        val wifi = getSystemService(WifiManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return R.string.nearby_lab_android_required
        if (!supportsSecondary() && !supportsNativeP2p()) {
            return R.string.nearby_lab_concurrency_required
        }
        if (wifi?.isWifiEnabled != true) return R.string.nearby_lab_enable_wifi
        return if (direct.isChecked) R.string.nearby_lab_p2p_ready else R.string.nearby_lab_ready
    }

    private fun supportsSecondary() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        getSystemService(WifiManager::class.java)?.isStaConcurrencyForLocalOnlyConnectionsSupported == true

    private fun supportsNativeP2p() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
        getSystemService(WifiP2pManager::class.java) != null

    private fun ready(message: Int) = message == R.string.nearby_lab_ready || message == R.string.nearby_lab_p2p_ready

    private fun refreshAvailability() {
        val message = availability()
        run.isEnabled = ready(message)
        status.setText(message)
    }

    private fun setRunning(running: Boolean) {
        updatingSwitch = true
        run.isChecked = running
        updatingSwitch = false
        direct.isEnabled = !running && supportsSecondary()
        ssid.isEnabled = !running
        passphrase.isEnabled = !running
    }

    private fun startProbe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || session != null) return
        if (!ready(availability())) {
            refreshAvailability()
            return
        }
        val name = ssid.text.toString().trim()
        val password = passphrase.text.toString().trim()
        if (!Regex("DIRECT-DF-[0-9a-f]{10}").matches(name) || !Regex("[0-9a-f]{32}").matches(password)) {
            status.setText(R.string.nearby_lab_invalid_credentials)
            return
        }
        if (direct.isChecked && !hasP2pPermission()) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            requestPermissions(permissions, 202)
            status.setText(R.string.nearby_lab_p2p_permission)
            return
        }
        if (!direct.isChecked && !supportsSecondary()) return
        passphrase.text.clear()
        val probe = Probe()
        session = probe
        setRunning(true)
        if (direct.isChecked) {
            status.setText(R.string.nearby_lab_p2p_waiting)
            probe.startNative(name, password)
        } else {
            status.setText(R.string.nearby_lab_waiting)
            startSecondary(probe, name, password)
        }
    }

    private fun hasP2pPermission() = checkSelfPermission(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSecondary(probe: Probe, name: String, password: String) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(
                WifiNetworkSpecifier.Builder().setSsid(name).setWpa2Passphrase(password).build(),
            )
            .build()
        probe.start(request)
    }

    override fun onDestroy() {
        destroying = true
        session?.finish(R.string.nearby_lab_cancelled)
        worker.shutdownNow()
        super.onDestroy()
    }

    private inner class Probe {
        private val defaultNetwork = connectivity.activeNetwork
        private val wasValidated = defaultNetwork?.let(connectivity::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        private var selected: Network? = null
        private var properties: LinkProperties? = null
        private var isolated = false
        private var started = false
        private var registered = false
        private var monitoringDefault = false
        private var closeNative: (((Boolean) -> Unit) -> Unit)? = null
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

        private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == defaultNetwork) finish(R.string.nearby_lab_primary_changed)
            }

            override fun onAvailable(network: Network) {
                if (network != defaultNetwork) finish(R.string.nearby_lab_primary_changed)
            }
        }

        private fun watchPrimary() {
            connectivity.registerDefaultNetworkCallback(defaultCallback, handler)
            monitoringDefault = true
            handler.post(tick)
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        fun startNative(name: String, password: String) {
            try {
                watchPrimary()
                val manager = getSystemService(WifiP2pManager::class.java)
                val channel = manager?.initialize(this@NearbyLinkLabActivity, Looper.getMainLooper()) {
                    finish(R.string.nearby_lab_lost)
                }
                if (manager == null || channel == null) {
                    finish(R.string.nearby_lab_p2p_rejected)
                    return
                }
                val native = NativeP2pLab(manager, channel, handler, name, { endpoint ->
                    exchange(endpoint.host) {
                        Socket().apply {
                            try {
                                bind(InetSocketAddress(endpoint.local, 0))
                            } catch (error: IOException) {
                                close()
                                throw error
                            }
                        }
                    }
                }, ::finish)
                closeNative = native::close
                native.connect(password)
            } catch (_: SecurityException) {
                finish(R.string.nearby_lab_p2p_permission)
            }
        }

        fun start(request: NetworkRequest) {
            try {
                watchPrimary()
                connectivity.requestNetwork(request, callback, handler, 75_000)
                registered = true
            } catch (_: SecurityException) {
                finish(R.string.nearby_lab_no_link)
            } catch (_: IllegalArgumentException) {
                finish(R.string.nearby_lab_no_link)
            }
        }

        private fun defaultUnchanged(): Boolean {
            val current = connectivity.activeNetwork
            return current == defaultNetwork && (selected == null || current != selected) &&
                (!wasValidated || current?.let(connectivity::getNetworkCapabilities)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        }

        private fun maybeExchange() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val network = selected ?: return
            if (finished || started || !isolated) return
            if (!defaultUnchanged()) {
                finish(R.string.nearby_lab_primary_changed)
                return
            }
            val link = properties ?: return
            val dhcp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) link.dhcpServerAddress else null
            val candidates = listOfNotNull(dhcp) +
                link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }
            val host = candidates.firstOrNull { address ->
                address.isSiteLocalAddress &&
                    link.linkAddresses.none { it.address == address } &&
                    link.routes.any { route ->
                        !route.isDefaultRoute && !route.hasGateway() && route.destination.contains(address)
                    }
            } ?: return // Wait for DHCP; never resolve via DNS or use the default network.
            exchange(host) { network.socketFactory.createSocket() }
        }

        private fun exchange(host: Inet4Address, createSocket: () -> Socket) {
            if (finished || started) return
            if (!defaultUnchanged()) {
                finish(R.string.nearby_lab_primary_changed)
                return
            }
            started = true
            status.setText(R.string.nearby_lab_exchanging)
            worker.execute {
                val result = try {
                    connect(host, createSocket).use {
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

        private fun connect(host: Inet4Address, createSocket: () -> Socket): Socket {
            val stopAt = minOf(expires, SystemClock.elapsedRealtime() + 20_000)
            while (!finished && SystemClock.elapsedRealtime() < stopAt) {
                val candidate = synchronized(socketLock) {
                    if (finished) throw IOException("Probe closed")
                    createSocket().also { socket = it }
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
            if (monitoringDefault) runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
            val finalize = { clean: Boolean ->
                if (session === this) {
                    session = null
                    if (!destroying) {
                        setRunning(false)
                        run.isEnabled = ready(availability())
                        status.setText(if (clean) message else R.string.nearby_lab_p2p_cleanup_failed)
                    }
                }
            }
            val native = closeNative
            if (native != null) {
                run.isEnabled = false
                status.setText(R.string.nearby_lab_stopping)
                native(finalize)
            } else {
                finalize(true)
            }
        }
    }
}
