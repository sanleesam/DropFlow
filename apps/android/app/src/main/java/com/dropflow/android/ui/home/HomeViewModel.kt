package com.dropflow.android.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.network.discovery.DeviceDiscoveryListener
import com.dropflow.android.core.network.discovery.NsdDeviceDiscoveryEngine
import com.dropflow.android.core.network.transfer.TransferEngine
import com.dropflow.android.storage.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val transferEngine: TransferEngine
) : ViewModel(), DeviceDiscoveryListener {

    private val appContext: Context = context.applicationContext

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val discoveryEngine = NsdDeviceDiscoveryEngine(context.applicationContext, this)

    init {
        viewModelScope.launch {
            val stableUuid = userPreferencesRepository.getOrCreateDeviceUuid()

            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                transferEngine.updateAuthorizationPolicy(prefs.requireConfirmation)
                _uiState.update { current -> current.copy(
                    localDeviceName = prefs.deviceName,
                    localUuid = prefs.deviceUuid.ifEmpty { stableUuid }
                ) }

                if (discoveryEngine.localUuid.isEmpty()) {
                    discoveryEngine.initialize(stableUuid)
                    discoveryEngine.start(prefs.deviceName)
                } else if (discoveryEngine.currentDeviceName != prefs.deviceName) {
                    // Re-register only when the advertised name actually changed.
                    discoveryEngine.restartBroadcasting(prefs.deviceName)
                }
            }
        }

        viewModelScope.launch {
            transferEngine.serverStatus.collectLatest { status ->
                _uiState.update { current -> current.copy(serverStatus = status) }
            }
        }

        viewModelScope.launch {
            transferEngine.activeSessions.collectLatest { sessions ->
                _uiState.update { current -> current.copy(activeSessions = sessions) }
            }
        }

        viewModelScope.launch {
            transferEngine.pendingTransferRequest.collectLatest { request ->
                _uiState.update { current -> current.copy(pendingTransferRequest = request) }
            }
        }

        viewModelScope.launch {
            transferEngine.activeProgress.collectLatest { progress ->
                _uiState.update { current -> current.copy(activeProgress = progress) }
            }
        }

        viewModelScope.launch {
            transferEngine.outgoingTransferActive.collectLatest { active ->
                _uiState.update { current -> current.copy(outgoingTransferActive = active) }
            }
        }

        viewModelScope.launch {
            transferEngine.lastTransferResult.collectLatest { result ->
                _uiState.update { current -> current.copy(
                    transferResultMessage = result?.message,
                    transferResultId = result?.sessionId,
                    isTransferResultError = result?.isError == true
                ) }
            }
        }
    }

    fun acceptTransfer(request: com.dropflow.android.core.network.transfer.TransferRequestPayload) {
        transferEngine.respondToRequest(request, accept = true)
    }

    fun declineTransfer(request: com.dropflow.android.core.network.transfer.TransferRequestPayload) {
        transferEngine.respondToRequest(request, accept = false)
    }

    fun cancelActiveTransfer() {
        val activeSessionId = _uiState.value.activeProgress?.sessionId ?: return
        transferEngine.cancelActiveTransfer(activeSessionId)
    }

    fun selectDevice(deviceId: String) {
        _uiState.update { current -> current.copy(
            selectedDeviceId = if (current.selectedDeviceId == deviceId) null else deviceId
        ) }
    }

    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val selectedId = _uiState.value.selectedDeviceId ?: run {
            android.util.Log.w("DropFlowHomeVM", "sendFiles: no device selected")
            return
        }
        val device = _uiState.value.discoveredDevices.firstOrNull { it.id == selectedId } ?: run {
            android.util.Log.w("DropFlowHomeVM", "sendFiles: selected device $selectedId not found in discovered list")
            return
        }
        android.util.Log.d("DropFlowHomeVM", "sendFiles: ${uris.size} files -> ${device.name}")
        transferEngine.sendFiles(
            localDeviceId = _uiState.value.localUuid,
            localDeviceName = _uiState.value.localDeviceName,
            device = device,
            uris = uris,
            contentResolver = appContext.contentResolver
        )
    }

    fun refreshDevices() {
        _uiState.update { current -> current.copy(isSearching = true, discoveredDevices = emptyList(), selectedDeviceId = null, errorMessage = null) }
        discoveryEngine.restartBrowsing()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryEngine.stop()
    }

    override fun onDiscoveryStarted() {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Discovery started")
        _uiState.update { current -> current.copy(isSearching = true, errorMessage = null) }
    }

    override fun onDiscoveryStopped() {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Discovery stopped")
        _uiState.update { current -> current.copy(isSearching = false) }
    }

    override fun onDeviceFound(device: Device) {
        _uiState.update { current ->
            val devices = current.discoveredDevices.toMutableList()
            val index = devices.indexOfFirst { it.id == device.id }
            if (index >= 0) devices[index] = device else devices.add(device)
            current.copy(discoveredDevices = devices)
        }
    }

    override fun onDeviceLost(deviceId: String) {
        _uiState.update { current -> current.copy(
            discoveredDevices = current.discoveredDevices.filterNot { it.id == deviceId },
            selectedDeviceId = current.selectedDeviceId.takeUnless { it == deviceId }
        ) }
    }

    override fun onAllDevicesCleared() {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] All devices cleared. Active count: 0")
        _uiState.update { current -> current.copy(
            discoveredDevices = emptyList(),
            selectedDeviceId = null
        ) }
    }

    override fun onError(message: String) {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Discovery error: $message")
        _uiState.update { current -> current.copy(errorMessage = message, isSearching = false) }
    }

    companion object {
        /** Factory so the instance survives configuration changes via ViewModelProvider. */
        fun factory(
            context: Context,
            userPreferencesRepository: UserPreferencesRepository,
            transferEngine: TransferEngine
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(context, userPreferencesRepository, transferEngine)
            }
        }
    }
}
