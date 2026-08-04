package com.dropflow.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.model.DeviceStatus
import com.dropflow.android.core.model.DeviceType
import com.dropflow.android.storage.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class HomeUiState(
    val localDeviceName: String = "Android Phone",
    val localUuid: String = "uuid-android-001",
    val selectedDeviceId: String? = null,
    val discoveredDevices: List<Device> = emptyList(),
    val isSearching: Boolean = true
)

class HomeViewModel(
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                _uiState.value = _uiState.value.copy(
                    localDeviceName = prefs.deviceName
                )
            }
        }
        loadInitialDevices()
    }

    private fun loadInitialDevices() {
        // Foundation preview devices (Networking & mDNS will populate in Phase 2)
        _uiState.value = _uiState.value.copy(
            discoveredDevices = listOf(
                Device(
                    id = "dev-macbook-pro",
                    name = "Sanlee's MacBook Pro",
                    type = DeviceType.LAPTOP,
                    status = DeviceStatus.ONLINE,
                    lastSeen = "Just now"
                ),
                Device(
                    id = "dev-windows-pc",
                    name = "Desktop Workstation",
                    type = DeviceType.DESKTOP,
                    status = DeviceStatus.RECENTLY_SEEN,
                    lastSeen = "2 mins ago"
                )
            )
        )
    }

    fun selectDevice(deviceId: String) {
        val current = _uiState.value.selectedDeviceId
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = if (current == deviceId) null else deviceId
        )
    }
}
