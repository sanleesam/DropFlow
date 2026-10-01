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

    // Devices the user chose to trust via the incoming-transfer prompt. Kept here so
    // persisted-preference updates can re-emit the full policy without losing them.
    private val trustedDeviceIds = mutableSetOf<String>()

    private var latestPreferences: com.dropflow.android.storage.UserPreferences =
        com.dropflow.android.storage.UserPreferences(
            deviceUuid = "",
            deviceName = "",
            requireConfirmation = true,
            autoAcceptTrustedDevices = true,
            askBeforeOverwrite = true,
            autoOpenCompleted = false,
            accentColor = "blue"
        )

    init {
        viewModelScope.launch {
            val stableUuid = userPreferencesRepository.getOrCreateDeviceUuid()

            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                latestPreferences = prefs
                _uiState.value = _uiState.value.copy(
                    localDeviceName = prefs.deviceName,
                    localUuid = prefs.deviceUuid.ifEmpty { stableUuid }
                )

                if (discoveryEngine.localUuid.isEmpty()) {
                    discoveryEngine.initialize(stableUuid, prefs.deviceName)
                    discoveryEngine.start(prefs.deviceName)
                } else if (discoveryEngine.currentDeviceName != prefs.deviceName) {
                    // Re-register only when the advertised name actually changed.
                    discoveryEngine.restartBroadcasting(prefs.deviceName)
                }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                transferEngine.updateAuthorizationPolicy(
                    requireConfirmation = prefs.requireConfirmation,
                    autoAcceptTrustedDevices = prefs.autoAcceptTrustedDevices,
                    trustedDeviceIds = trustedDeviceIds.toSet()
                )
            }
        }

        viewModelScope.launch {
            transferEngine.serverStatus.collectLatest { status ->
                _uiState.value = _uiState.value.copy(serverStatus = status)
            }
        }

        viewModelScope.launch {
            transferEngine.activeSessions.collectLatest { sessions ->
                _uiState.value = _uiState.value.copy(activeSessions = sessions)
            }
        }

        viewModelScope.launch {
            transferEngine.pendingTransferRequest.collectLatest { request ->
                _uiState.value = _uiState.value.copy(pendingTransferRequest = request)
            }
        }

        viewModelScope.launch {
            transferEngine.activeProgress.collectLatest { progress ->
                _uiState.value = _uiState.value.copy(activeProgress = progress)
            }
        }

        viewModelScope.launch {
            transferEngine.outgoingTransferActive.collectLatest { active ->
                _uiState.value = _uiState.value.copy(outgoingTransferActive = active)
            }
        }

        viewModelScope.launch {
            transferEngine.lastTransferResult.collectLatest { result ->
                _uiState.value = _uiState.value.copy(
                    transferResultMessage = result?.message,
                    isTransferResultError = result?.isError == true
                )
            }
        }
    }

    fun acceptTransfer(trustDevice: Boolean = false) {
        val currentRequest = _uiState.value.pendingTransferRequest ?: return
        if (trustDevice && currentRequest.senderId.isNotBlank()) {
            trustedDeviceIds += currentRequest.senderId
            val prefs = latestPreferences
            transferEngine.updateAuthorizationPolicy(
                requireConfirmation = prefs.requireConfirmation,
                autoAcceptTrustedDevices = prefs.autoAcceptTrustedDevices,
                trustedDeviceIds = trustedDeviceIds.toSet()
            )
        }
        transferEngine.acceptPendingTransfer(currentRequest.sessionId, trustDevice)
    }

    fun declineTransfer(reason: String = "User declined transfer") {
        val currentRequest = _uiState.value.pendingTransferRequest ?: return
        transferEngine.declinePendingTransfer(currentRequest.sessionId, reason)
    }

    fun cancelActiveTransfer() {
        val activeSessionId = _uiState.value.activeProgress?.sessionId ?: return
        transferEngine.cancelActiveTransfer(activeSessionId)
    }

    fun selectDevice(deviceId: String) {
        val current = _uiState.value.selectedDeviceId
        _uiState.value = _uiState.value.copy(
            selectedDeviceId = if (current == deviceId) null else deviceId
        )
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
        _uiState.value = _uiState.value.copy(isSearching = true, discoveredDevices = emptyList())
        discoveryEngine.restartBrowsing()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryEngine.stop()
    }

    override fun onDiscoveryStarted() {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Discovery started")
        _uiState.value = _uiState.value.copy(isSearching = true, errorMessage = null)
    }

    override fun onDiscoveryStopped() {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Discovery stopped")
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
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Device updated/added: ${device.name} (UUID=${device.id}). Active count: ${currentList.size}")
        _uiState.value = _uiState.value.copy(discoveredDevices = currentList)
    }

    override fun onDeviceLost(deviceId: String) {
        val currentList = _uiState.value.discoveredDevices.filterNot { it.id == deviceId }
        val updatedSelected = if (_uiState.value.selectedDeviceId == deviceId) null else _uiState.value.selectedDeviceId
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Device lost: UUID=$deviceId. Active count: ${currentList.size}")
        _uiState.value = _uiState.value.copy(
            discoveredDevices = currentList,
            selectedDeviceId = updatedSelected
        )
    }

    override fun onAllDevicesCleared() {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] All devices cleared. Active count: 0")
        _uiState.value = _uiState.value.copy(
            discoveredDevices = emptyList(),
            selectedDeviceId = null
        )
    }

    override fun onError(message: String) {
        android.util.Log.d("DropFlowHomeVM", "[UI State Update] Discovery error: $message")
        _uiState.value = _uiState.value.copy(errorMessage = message, isSearching = false)
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
