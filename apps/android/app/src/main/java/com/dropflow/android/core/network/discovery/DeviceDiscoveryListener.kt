package com.dropflow.android.core.network.discovery

import com.dropflow.android.core.model.Device

interface DeviceDiscoveryListener {
    fun onDiscoveryStarted()
    fun onDiscoveryStopped()
    fun onDeviceFound(device: Device)
    fun onDeviceLost(deviceId: String)
    fun onError(message: String)
}
