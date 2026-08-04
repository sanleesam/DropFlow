package com.dropflow.android.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.network.discovery.DeviceDiscoveryListener
import com.dropflow.android.core.network.discovery.NsdDeviceDiscoveryEngine
import com.dropflow.android.storage.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeViewModel(
    context: Context,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel(), DeviceDiscoveryListener {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val discoveryEngine = NsdDeviceDiscoveryEngine(context.applicationContext, this)

    init {
        viewModelScope.launch {
            val stableUuid = userPreferencesRepository.getOrCreateDeviceUuid()
            
            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                _uiState.value = _uiState.value.copy(
                    localDeviceName = prefs.deviceName,
                    localUuid = prefs.deviceUuid.ifEmpty { stableUuid }
                )

                if (discoveryEngine.localUuid.isEmpty()) {
                    discoveryEngine.initialize(stableUuid, prefs.deviceName)
                    discoveryEngine.start(prefs.deviceName)
                } else {
                    discoveryEngine.startBroadcasting(prefs.deviceName)
                }
            }
        }
    }

    fun selectDevice(deviceId: String) {
        val current = _uiState.value.selectedDeviceId
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = if (current == deviceId) null else deviceId
        )
    }

    fun refreshDevices() {
        _uiState.value = _uiState.value.copy(isSearching = true, discoveredDevices = emptyList())
        discoveryEngine.stopBrowsing()
        discoveryEngine.startBrowsing()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryEngine.stop()
    }

    // --- DeviceDiscoveryListener Extensions ---

    override fun onDiscoveryStarted() {
        _uiState.value = _uiState.value.copy(isSearching = true, errorMessage = null)
    }

    override fun onDiscoveryStopped() {
        _uiState.value = _uiState.value.copy(isSearching = false)
    }

    override fun onDeviceFound(device: Device) {
        val currentList = _uiState.value.discoveredDevices.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == device.id }
        if (existingIndex >= 0) {
            currentList[existingIndex] = device
        } else {
            currentList.add(device)
        }
        _uiState.value = _uiState.value.copy(discoveredDevices = currentList)
    }

    override fun onDeviceLost(deviceId: String) {
        val currentList = _uiState.value.discoveredDevices.filterNot { it.id == deviceId }
        val updatedSelected = if (_uiState.value.selectedDeviceId == deviceId) null else _uiState.value.selectedDeviceId
        _uiState.value = _uiState.value.copy(
            discoveredDevices = currentList,
            selectedDeviceId = updatedSelected
        )
    }

    override fun onError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message, isSearching = false)
    }
}
