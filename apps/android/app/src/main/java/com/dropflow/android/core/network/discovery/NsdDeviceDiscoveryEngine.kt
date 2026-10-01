package com.dropflow.android.core.network.discovery

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dropflow.android.BuildConfig
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.model.DeviceAddress
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.core.model.DeviceType
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "DropFlowNSD"
private const val SERVICE_TYPE = "_dropflow._tcp."
private const val PROTOCOL_VERSION = "DFP/1"
private const val DEFAULT_PORT = 42100

/** NSD stop is asynchronous; restarting sooner fails with FAILURE_ALREADY_ACTIVE. */
private const val RESCAN_DELAY_MS = 400L
/** Max automatic restarts when NSD reports the previous discovery is still active. */
private const val DISCOVERY_RETRY_LIMIT = 2

class NsdDeviceDiscoveryEngine(
    context: Context,
    private val listener: DeviceDiscoveryListener
) {
    private val appContext: Context = context.applicationContext
    private val nsdManager: NsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager: ConnectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var localUuid: String = ""
        private set

    /** Name currently advertised via mDNS, or null while not broadcasting. */
    var currentDeviceName: String? = null
        private set

    // Discovery registry state:
    // discoveredPeersMap: UUID -> Device
    private val discoveredPeersMap = ConcurrentHashMap<String, Device>()
    // serviceToPeerMap: serviceName -> UUID
    private val serviceToPeerMap = ConcurrentHashMap<String, String>()
    // pendingResolutions: set of serviceNames currently queued or being resolved
    private val pendingResolutions = ConcurrentHashMap.newKeySet<String>()

    // Sequential resolution queue to avoid NsdManager.FAILURE_ALREADY_ACTIVE (code 3)
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolveLock = Any()
    private var isResolving = false

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var discoveryRetryCount = 0

    private var isBroadcasting = false
    private var isBrowsing = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val localDeviceType: String
        get() {
            val isTablet = (appContext.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
            return if (isTablet) "tablet" else "phone"
        }

    fun initialize(uuid: String, deviceName: String, port: Int = DEFAULT_PORT) {
        this.localUuid = uuid
    }

    fun start(deviceName: String = currentDeviceName ?: "Android Device", port: Int = DEFAULT_PORT) {
        startBroadcasting(deviceName, port)
        startBrowsing()
    }

    fun stop() {
        stopBroadcasting()
        stopBrowsing()
        unregisterNetworkCallback()
    }

    fun startBroadcasting(deviceName: String = currentDeviceName ?: "Android Device", port: Int = DEFAULT_PORT) {
        if (isBroadcasting || localUuid.isEmpty()) return
        currentDeviceName = deviceName

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "DropFlow-${localUuid.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("device_uuid", localUuid)
            setAttribute("device_id", localUuid)
            setAttribute("device_name", deviceName.take(64))
            setAttribute("device_type", localDeviceType)
            setAttribute("platform", "Android")
            setAttribute("operating_system", "Android ${Build.VERSION.RELEASE}")
            setAttribute("application_version", BuildConfig.VERSION_NAME)
            setAttribute("version", PROTOCOL_VERSION)
            setAttribute("protocol_version", PROTOCOL_VERSION)
            setAttribute("capabilities", "tcp_v1,sha256,chunked")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "[DropFlowNSD] mDNS Service registered: ${NsdServiceInfo.serviceName}")
                isBroadcasting = true
            }

            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                Log.e(TAG, "[DropFlowNSD] mDNS Registration failed code: $arg1")
                isBroadcasting = false
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "[DropFlowNSD] mDNS Service unregistered")
                isBroadcasting = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[DropFlowNSD] mDNS Unregistration failed code: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "[DropFlowNSD] Failed to register mDNS service", e)
        }
    }

    fun stopBroadcasting() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "[DropFlowNSD] Safe warning on unregisterService", e)
            }
        }
        registrationListener = null
        isBroadcasting = false
    }

    /** Re-registers the mDNS service so peers immediately see the new [deviceName]. */
    fun restartBroadcasting(deviceName: String, port: Int = DEFAULT_PORT) {
        Log.d(TAG, "[DropFlowNSD] Restarting advertisement with name '$deviceName'")
        stopBroadcasting()
        startBroadcasting(deviceName, port)
    }

    fun startBrowsing() {
        if (isBrowsing) return
        discoveryRetryCount = 0

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "[DropFlowNSD] mDNS Discovery started: $regType")
                isBrowsing = true
                mainHandler.post { listener.onDiscoveryStarted() }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "[DropFlowNSD] service discovered: ${service.serviceName}")
                if (service.serviceType.contains("_dropflow._tcp")) {
                    enqueueResolve(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val serviceName = service.serviceName
                Log.d(TAG, "[DropFlowNSD] service removed: $serviceName")
                pendingResolutions.remove(serviceName)

                val peerUuid = serviceToPeerMap.remove(serviceName)
                if (peerUuid != null) {
                    if (!serviceToPeerMap.values.contains(peerUuid)) {
                        val removedDevice = discoveredPeersMap.remove(peerUuid)
                        if (removedDevice != null) {
                            Log.d(TAG, "[DropFlowNSD] device removed: $peerUuid (${removedDevice.name})")
                        } else {
                            Log.d(TAG, "[DropFlowNSD] device removed: $peerUuid")
                        }
                        mainHandler.post { listener.onDeviceLost(peerUuid) }
                    } else {
                        Log.d(TAG, "[DropFlowNSD] Service $serviceName removed but peer $peerUuid remains mapped via another service")
                    }
                } else {
                    Log.d(TAG, "[DropFlowNSD] Service removed before resolution completed or missing mapping: $serviceName")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "[DropFlowNSD] mDNS Discovery stopped")
                isBrowsing = false
                mainHandler.post { listener.onDiscoveryStopped() }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[DropFlowNSD] mDNS Start discovery failed code: $errorCode")
                isBrowsing = false

                // The previous discovery listener is often still shutting down;
                // retry automatically instead of surfacing a transient error.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && discoveryRetryCount < DISCOVERY_RETRY_LIMIT) {
                    discoveryRetryCount++
                    Log.d(TAG, "[DropFlowNSD] Discovery busy, retrying (attempt $discoveryRetryCount)")
                    mainHandler.postDelayed({ startBrowsing() }, RESCAN_DELAY_MS * discoveryRetryCount)
                    return
                }

                mainHandler.post { listener.onError("Discovery failed code $errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "[DropFlowNSD] mDNS Stop discovery failed code: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "[DropFlowNSD] Failed to start mDNS discovery", e)
        }
    }

    fun stopBrowsing() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "[DropFlowNSD] Safe warning on stopServiceDiscovery", e)
            }
        }
        discoveryListener = null
        isBrowsing = false
        clearDiscoveryState()
    }

    /**
     * Restarts service discovery (used by manual rescans). NSD stop is
     * asynchronous, so the restart is delayed briefly to avoid
     * [NsdManager.FAILURE_ALREADY_ACTIVE].
     */
    fun restartBrowsing() {
        Log.d(TAG, "[DropFlowNSD] Manual rescan requested")
        stopBrowsing()
        mainHandler.postDelayed({ startBrowsing() }, RESCAN_DELAY_MS)
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        pendingResolutions.add(serviceInfo.serviceName)
        resolveQueue.add(serviceInfo)
        processNextResolve()
    }

    private fun processNextResolve() {
        synchronized(resolveLock) {
            if (isResolving) return
            val serviceInfo = resolveQueue.poll() ?: return
            val serviceName = serviceInfo.serviceName

            if (!pendingResolutions.contains(serviceName)) {
                Log.d(TAG, "[DropFlowNSD] Service $serviceName was lost while queued, skipping resolution")
                return processNextResolve()
            }

            isResolving = true
            Log.d(TAG, "[DropFlowNSD] Resolving service: $serviceName")

            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(failedService: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "[DropFlowNSD] mDNS Resolve failed for ${failedService.serviceName} code $errorCode")
                    pendingResolutions.remove(failedService.serviceName)
                    synchronized(resolveLock) {
                        isResolving = false
                    }
                    processNextResolve()
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    try {
                        handleServiceResolved(resolved)
                    } catch (e: Exception) {
                        Log.e(TAG, "[DropFlowNSD] Exception inside handleServiceResolved", e)
                    } finally {
                        pendingResolutions.remove(resolved.serviceName)
                        synchronized(resolveLock) {
                            isResolving = false
                        }
                        processNextResolve()
                    }
                }
            }

            try {
                nsdManager.resolveService(serviceInfo, resolveListener)
            } catch (e: Exception) {
                Log.e(TAG, "[DropFlowNSD] Failed to invoke resolveService for ${serviceInfo.serviceName}", e)
                pendingResolutions.remove(serviceName)
                isResolving = false
                processNextResolve()
            }
        }
    }

    private fun handleServiceResolved(resolved: NsdServiceInfo) {
        Log.d(TAG, "[DropFlowNSD] service resolved: ${resolved.serviceName} at ${resolved.host}:${resolved.port}")

        val txtRecord = resolved.attributes
        val deviceUuid = getStringAttr(txtRecord, "device_uuid")
            ?: getStringAttr(txtRecord, "device_id")

        if (deviceUuid.isNullOrBlank()) {
            Log.w(TAG, "[DropFlowNSD] Peer rejected: missing or blank device_uuid/device_id for ${resolved.serviceName}")
            return
        }

        val cleanUuid = deviceUuid.trim()

        if (isSelfPeer(cleanUuid)) {
            Log.d(TAG, "[DropFlowNSD] Self device ignored: $cleanUuid")
            return
        }

        val version = getStringAttr(txtRecord, "version")
            ?: getStringAttr(txtRecord, "protocol_version")
            ?: PROTOCOL_VERSION

        if (!version.startsWith("DFP/")) {
            Log.w(TAG, "[DropFlowNSD] Peer rejected: unsupported protocol version '$version'")
            return
        }

        val port = resolved.port
        if (port <= 0) {
            Log.w(TAG, "[DropFlowNSD] Peer rejected: invalid port $port")
            return
        }

        val deviceName = getStringAttr(txtRecord, "device_name")?.ifBlank { null } ?: resolved.serviceName
        val deviceTypeRaw = getStringAttr(txtRecord, "device_type")
        val platformAttr = getStringAttr(txtRecord, "platform") ?: "Desktop"
        val osAttr = getStringAttr(txtRecord, "operating_system") ?: platformAttr
        val capsAttr = getStringAttr(txtRecord, "capabilities") ?: "tcp_v1,sha256"

        val resolvedAddressList = mutableListOf<DeviceAddress>()
        resolved.host?.let { host ->
            val ipStr = host.hostAddress
            if (!ipStr.isNullOrEmpty() && !host.isLoopbackAddress && !host.isAnyLocalAddress && !host.isMulticastAddress) {
                val family = if (ipStr.contains(":")) "ipv6" else "ipv4"
                resolvedAddressList.add(DeviceAddress(address = ipStr, family = family))
            }
        }

        if (resolvedAddressList.isEmpty()) {
            Log.w(TAG, "[DropFlowNSD] Peer rejected: no usable IP addresses for ${resolved.serviceName}")
            return
        }

        val typeEnum = determineDeviceType(deviceTypeRaw, deviceName, osAttr, platformAttr)
        val serviceName = resolved.serviceName

        val previousUuid = serviceToPeerMap.put(serviceName, cleanUuid)
        if (previousUuid != null && previousUuid != cleanUuid) {
            if (!serviceToPeerMap.values.contains(previousUuid)) {
                discoveredPeersMap.remove(previousUuid)
                Log.d(TAG, "[DropFlowNSD] Peer identity replaced for service $serviceName: previous UUID $previousUuid removed")
                mainHandler.post { listener.onDeviceLost(previousUuid) }
            }
        }

        val existingDevice = discoveredPeersMap[cleanUuid]
        val isNew = existingDevice == null
        val updatedDevice = Device(
            id = cleanUuid,
            name = deviceName,
            type = typeEnum,
            status = DeviceStatus.ONLINE,
            lastSeen = "Now",
            addresses = resolvedAddressList,
            port = port,
            version = version,
            platform = platformAttr,
            operatingSystem = osAttr,
            capabilities = capsAttr
        )

        val isChanged = existingDevice != updatedDevice

        discoveredPeersMap[cleanUuid] = updatedDevice

        if (isNew) {
            Log.d(TAG, "[DropFlowNSD] device added: ${updatedDevice.name} (UUID=${updatedDevice.id}, type=${updatedDevice.type})")
            mainHandler.post { listener.onDeviceFound(updatedDevice) }
        } else if (isChanged) {
            Log.d(TAG, "[DropFlowNSD] device updated: ${updatedDevice.name} (UUID=${updatedDevice.id}, type=${updatedDevice.type})")
            mainHandler.post { listener.onDeviceFound(updatedDevice) }
        } else {
            Log.d(TAG, "[DropFlowNSD] Peer re-resolved unchanged: ${updatedDevice.name} (UUID=${updatedDevice.id})")
        }
    }

    private fun isSelfPeer(uuid: String): Boolean {
        if (localUuid.isBlank()) return false
        return uuid.equals(localUuid, ignoreCase = true)
    }

    private fun determineDeviceType(
        rawType: String?,
        name: String,
        os: String,
        platform: String
    ): DeviceType {
        if (!rawType.isNullOrBlank()) {
            when (rawType.trim().lowercase()) {
                "phone", "mobile" -> return DeviceType.PHONE
                "tablet" -> return DeviceType.TABLET
                "laptop" -> return DeviceType.LAPTOP
                "desktop" -> return DeviceType.DESKTOP
            }
        }

        val lowerName = name.lowercase()
        if (lowerName.contains("macbook") || lowerName.contains("book") || lowerName.contains("laptop") || lowerName.contains("surface laptop")) {
            return DeviceType.LAPTOP
        }
        if (lowerName.contains("mac mini") || lowerName.contains("imac") || lowerName.contains("mac studio") || lowerName.contains("mac pro") || lowerName.contains("desktop") || lowerName.contains("pc")) {
            return DeviceType.DESKTOP
        }
        if (platform.equals("Android", ignoreCase = true) || platform.equals("iOS", ignoreCase = true)) {
            return DeviceType.PHONE
        }
        return DeviceType.DESKTOP
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "[DropFlowNSD] Wi-Fi Network available, triggering mDNS auto-rediscovery")
                mainHandler.postDelayed({
                    if (isBrowsing) {
                        stopBrowsing()
                        startBrowsing()
                    }
                    if (isBroadcasting || localUuid.isNotEmpty()) {
                        restartBroadcasting(currentDeviceName ?: "Android Device")
                    }
                }, 500)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "[DropFlowNSD] Wi-Fi Network lost")
                clearDiscoveryState()
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "[DropFlowNSD] Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "[DropFlowNSD] Safe warning on unregisterNetworkCallback", e)
            }
        }
        networkCallback = null
    }

    private fun clearDiscoveryState() {
        synchronized(resolveLock) {
            resolveQueue.clear()
            pendingResolutions.clear()
            isResolving = false
        }
        serviceToPeerMap.clear()
        discoveredPeersMap.clear()
        mainHandler.post { listener.onAllDevicesCleared() }
    }

    private fun getStringAttr(attributes: Map<String, ByteArray>?, key: String): String? {
        if (attributes == null) return null
        val bytes = attributes[key]
            ?: attributes[key.lowercase()]
            ?: attributes[key.uppercase()]
            ?: attributes.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            ?: return null
        return String(bytes, StandardCharsets.UTF_8).trim()
    }
}
