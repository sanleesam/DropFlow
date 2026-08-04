package com.dropflow.android.core.network.transfer

import com.dropflow.android.core.model.TransferItem

interface TransferEngineListener {
    fun onIncomingTransferRequested(transfer: TransferItem)
    fun onTransferProgress(transferId: String, bytesTransferred: Long, totalBytes: Long)
    fun onTransferCompleted(transfer: TransferItem)
    fun onTransferFailed(transferId: String, error: String)
}
