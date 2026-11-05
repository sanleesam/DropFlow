package com.dropflow.android.core.network.transfer

import java.io.InputStream

internal fun readAuthorizationResponse(input: InputStream, setReadTimeout: (Int) -> Unit): FrameHeader {
    setReadTimeout(Protocol.APPROVAL_TIMEOUT_MS)
    try {
        val header = FrameUtils.readHeader(input)
        FrameUtils.readPayload(input, header.payloadLength)
        return header
    } finally {
        setReadTimeout(Protocol.READ_TIMEOUT_MS)
    }
}
