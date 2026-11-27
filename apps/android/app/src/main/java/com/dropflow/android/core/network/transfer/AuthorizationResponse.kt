package com.dropflow.android.core.network.transfer

import java.io.InputStream

internal data class AuthorizationResponse(val tag: FrameTag, val payload: ByteArray)

internal fun readAuthorizationResponse(input: InputStream, setReadTimeout: (Int) -> Unit): AuthorizationResponse {
    setReadTimeout(Protocol.APPROVAL_TIMEOUT_MS)
    try {
        // A DFP/1.1 receiver may send keep-alives while it prepares its answer.
        val (tag, payload) = readReply(input)
        return AuthorizationResponse(tag, payload)
    } finally {
        setReadTimeout(Protocol.READ_TIMEOUT_MS)
    }
}
