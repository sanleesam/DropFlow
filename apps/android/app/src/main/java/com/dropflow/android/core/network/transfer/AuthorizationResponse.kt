package com.dropflow.android.core.network.transfer

import java.io.InputStream

internal data class AuthorizationResponse(val tag: FrameTag, val payload: ByteArray)

internal fun readAuthorizationResponse(input: InputStream, setReadTimeout: (Int) -> Unit): AuthorizationResponse {
    setReadTimeout(Protocol.APPROVAL_TIMEOUT_MS)
    try {
        val header = FrameUtils.readHeader(input)
        return AuthorizationResponse(header.tag, FrameUtils.readPayload(input, header.payloadLength))
    } finally {
        setReadTimeout(Protocol.READ_TIMEOUT_MS)
    }
}
