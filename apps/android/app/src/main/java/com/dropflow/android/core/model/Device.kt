package com.dropflow.android.core.model

enum class DeviceType {
    DESKTOP,
    LAPTOP,
    PHONE,
    TABLET
}

enum class DeviceStatus {
    ONLINE,
    RECENTLY_SEEN,
    OFFLINE
}

data class DeviceAddress(
    val address: String,
    val family: String,
    val interfaceName: String? = null
)

data class Device(
    val id: String,
    val name: String,
    val type: DeviceType,
    val status: DeviceStatus = DeviceStatus.ONLINE,
    val lastSeen: String = "Now",
    val addresses: List<DeviceAddress> = emptyList(),
    val port: Int = 42100,
    val version: String = "DFP/1",
    val platform: String = "Android",
    val operatingSystem: String = "Android ${android.os.Build.VERSION.RELEASE}",
    val capabilities: String = "tcp_v1,sha256,chunked"
)
