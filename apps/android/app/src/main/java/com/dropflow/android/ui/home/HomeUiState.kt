package com.dropflow.android.ui.home

import com.dropflow.android.core.model.Device
import com.dropflow.android.core.network.transfer.ServerStatus
import com.dropflow.android.core.network.transfer.TransferProgressPayload
import com.dropflow.android.core.network.transfer.TransferRequestPayload
import com.dropflow.android.core.network.transfer.TransferSession

data class HomeUiState(
    val localDeviceName: String = "Android Phone",
    val localUuid: String = "",
    val isSearching: Boolean = true,
    val selectedDeviceId: String? = null,
    val discoveredDevices: List<Device> = emptyList(),
    val serverStatus: ServerStatus = ServerStatus.STOPPED,
    val activeSessions: List<TransferSession> = emptyList(),
    val pendingTransferRequest: TransferRequestPayload? = null,
    val activeProgress: TransferProgressPayload? = null,
    val outgoingTransferActive: Boolean = false,
    val transferResultMessage: String? = null,
    val isTransferResultError: Boolean = false,
    val errorMessage: String? = null
)
