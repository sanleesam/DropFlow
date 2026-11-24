package com.dropflow.android.core.network.transfer

import java.util.concurrent.atomic.AtomicReference

/** One transfer owns the shared prompt, progress, and cancellation controls. */
internal class TransferSlot(
    private val onBusy: () -> Unit = {},
    private val onIdle: () -> Unit = {},
) {
    private val owner = AtomicReference<String?>(null)
    val activeId: String? get() = owner.get()

    fun claim(id: String): Boolean = owner.compareAndSet(null, id).also { if (it) onBusy() }
    fun release(id: String) {
        // AtomicReference compares identity; use the stored value after checking its ID.
        val current = owner.get()
        if (current == id && owner.compareAndSet(current, null)) onIdle()
    }
}

internal data class IncomingDecision(val accepted: Boolean, val reason: String? = null)
