package com.dropflow.android.ui.home

import com.dropflow.android.core.model.Device

data class HomeUiState(
    val localDeviceName: String = "Android Phone",
    val localUuid: String = "uuid-android-001",
    val isDiscoverable: Boolean = true,
    val isSearching: Boolean = true,
    val selectedDeviceId: String? = null,
    val discoveredDevices: List<Device> = emptyList(),
    val errorMessage: String? = null
)
