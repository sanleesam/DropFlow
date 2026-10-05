package com.dropflow.android.core.network.transfer

import com.dropflow.android.core.model.TransferItem

interface TransferEngineListener {
    fun onTransferCompleted(transfer: TransferItem)
    fun onTransferFailed(transfer: TransferItem)
}
