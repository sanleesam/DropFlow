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

private const val TAG = "DropFlowNSD"
private const val SERVICE_TYPE = "_dropflow._tcp."
private const val PROTOCOL_VERSION = "DFP/1"
private const val DEFAULT_PORT = 42100

class NsdDeviceDiscoveryEngine(
    context: Context,
    private val listener: DeviceDiscoveryListener
) {
    private val appContext: Context = context.applicationContext
    private val nsdManager: NsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val connectivityManager: ConnectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var localUuid: String = ""
        private set

    private var currentDeviceName: String = "Android Device"
    private var currentPort: Int = DEFAULT_PORT

    private val discoveredPeersMap = ConcurrentHashMap<String, Device>()
    private val serviceNameToUuidMap = ConcurrentHashMap<String, String>()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        this.currentDeviceName = deviceName
        this.currentPort = port
        registerNetworkCallback()
    }

    fun start(deviceName: String = currentDeviceName, port: Int = currentPort) {
        this.currentDeviceName = deviceName
        this.currentPort = port
        startBroadcasting(deviceName, port)
        startBrowsing()
    }

    fun stop() {
        stopBroadcasting()
        stopBrowsing()
        unregisterNetworkCallback()
    }

    fun startBroadcasting(deviceName: String = currentDeviceName, port: Int = currentPort) {
        if (isBroadcasting || localUuid.isEmpty()) return
        this.currentDeviceName = deviceName

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
            setAttribute("protocol_version", PROTOCOL_VERSION)
            setAttribute("capabilities", "tcp_v1,sha256,chunked")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service registered: ${NsdServiceInfo.serviceName}")
                isBroadcasting = true
            }

            override fun onRegistrationFailed(arg0: NsdServiceInfo, arg1: Int) {
                Log.e(TAG, "mDNS Registration failed code: $arg1")
                isBroadcasting = false
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service unregistered")
                isBroadcasting = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Unregistration failed code: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service", e)
        }
    }

    fun stopBroadcasting() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "Safe warning on unregisterService", e)
            }
        }
        registrationListener = null
        isBroadcasting = false
    }

    fun startBrowsing() {
        if (isBrowsing) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Discovery started: $regType")
                isBrowsing = true
                mainHandler.post { listener.onDiscoveryStarted() }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service found: ${service.serviceName}")
                if (service.serviceType.contains("_dropflow._tcp")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service lost: ${service.serviceName}")
                val uuid = serviceNameToUuidMap.remove(service.serviceName) ?: service.serviceName
                discoveredPeersMap.remove(uuid)
                mainHandler.post { listener.onDeviceLost(uuid) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS Discovery stopped")
                isBrowsing = false
                mainHandler.post { listener.onDiscoveryStopped() }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Start discovery failed code: $errorCode")
                isBrowsing = false
                mainHandler.post { listener.onError("Discovery failed code $errorCode") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Stop discovery failed code: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS discovery", e)
        }
    }

    fun stopBrowsing() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.w(TAG, "Safe warning on stopServiceDiscovery", e)
            }
        }
        discoveryListener = null
        isBrowsing = false
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS Resolve failed for ${serviceInfo.serviceName} code $errorCode")
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service resolved: ${resolved.serviceName} at ${resolved.host}")

                val txtRecord = resolved.attributes
                val deviceUuid = getStringAttr(txtRecord, "device_uuid") ?: resolved.serviceName
                val deviceName = getStringAttr(txtRecord, "device_name") ?: resolved.serviceName
                val deviceTypeRaw = getStringAttr(txtRecord, "device_type") ?: "desktop"
                val platformAttr = getStringAttr(txtRecord, "platform") ?: "Desktop"
                val osAttr = getStringAttr(txtRecord, "operating_system") ?: platformAttr
                val version = getStringAttr(txtRecord, "version") ?: PROTOCOL_VERSION
                val capsAttr = getStringAttr(txtRecord, "capabilities") ?: "tcp_v1,sha256"

                // Filter out self-peer
                if (deviceUuid == localUuid || (localUuid.isNotEmpty() && deviceUuid.contains(localUuid))) {
                    Log.d(TAG, "Ignoring self-peer mDNS advertisement ($deviceUuid)")
                    return
                }

                serviceNameToUuidMap[resolved.serviceName] = deviceUuid

                val resolvedAddressList = mutableListOf<DeviceAddress>()
                resolved.host?.hostAddress?.let { ipStr ->
                    resolvedAddressList.add(DeviceAddress(address = ipStr, family = if (ipStr.contains(":")) "ipv6" else "ipv4"))
                }

                val typeEnum = when (deviceTypeRaw.lowercase()) {
                    "phone", "mobile" -> DeviceType.PHONE
                    "tablet" -> DeviceType.TABLET
                    "laptop" -> DeviceType.LAPTOP
                    else -> DeviceType.DESKTOP
                }

                val existingDevice = discoveredPeersMap[deviceUuid]
                val mergedAddresses = if (existingDevice != null) {
                    (existingDevice.addresses + resolvedAddressList).distinctBy { it.address }
                } else {
                    resolvedAddressList
                }

                val updatedDevice = Device(
                    id = deviceUuid,
                    name = deviceName,
                    type = typeEnum,
                    status = DeviceStatus.ONLINE,
                    lastSeen = "Now",
                    addresses = mergedAddresses,
                    port = resolved.port,
                    version = version,
                    platform = platformAttr,
                    operatingSystem = osAttr,
                    capabilities = capsAttr
                )

                discoveredPeersMap[deviceUuid] = updatedDevice
                mainHandler.post { listener.onDeviceFound(updatedDevice) }
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve mDNS service ${serviceInfo.serviceName}", e)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Wi-Fi Network available, triggering mDNS auto-rediscovery")
                mainHandler.postDelayed({
                    if (isBrowsing) {
                        stopBrowsing()
                        startBrowsing()
                    }
                    if (isBroadcasting) {
                        stopBroadcasting()
                        startBroadcasting(currentDeviceName, currentPort)
                    }
                }, 500)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Wi-Fi Network lost")
                discoveredPeersMap.clear()
                serviceNameToUuidMap.clear()
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Safe warning on unregisterNetworkCallback", e)
            }
        }
        networkCallback = null
    }

    private fun getStringAttr(attributes: Map<String, ByteArray>, key: String): String? {
        val bytes = attributes[key] ?: return null
        return String(bytes, StandardCharsets.UTF_8).trim()
    }
}
