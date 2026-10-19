package com.dropflow.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.dropflow.android.core.model.Device
import com.dropflow.android.core.model.DeviceAddress
import com.dropflow.android.core.model.DeviceType
import com.dropflow.android.ui.home.HomeViewModel
import java.io.File

/**
 * Separate test-APK fixture for emulator/host interoperability.
 * Supplies discovery metadata and optional approval decisions for a known peer.
 * File selection, DFP/1, authorization and storage use the real app methods.
 * Never packaged in a release APK.
 */
class InteropInstrumentation : Instrumentation() {
    private var peerPort = 0
    private lateinit var peerHost: String
    private lateinit var peerId: String
    private var decision = "manual"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        peerPort = arguments?.getString("peerPort")?.toIntOrNull() ?: 0
        peerHost = arguments?.getString("peerHost") ?: "10.0.2.2"
        peerId = arguments?.getString("peerId") ?: "mac-interop-test"
        decision = arguments?.getString("decision") ?: "manual"
        start()
    }

    override fun onStart() {
        try {
            require(peerPort in 1..65535) { "peerPort is required" }
            val stop = File(targetContext.filesDir, "stop-interop-fixture")
            stop.delete()
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val getter = MainActivity::class.java.getDeclaredMethod("getHomeViewModel").apply { isAccessible = true }
            val peer = Device(id = peerId, name = "Mac app · direct emulator test", type = DeviceType.DESKTOP,
                addresses = listOf(DeviceAddress(peerHost, "ipv4")), port = peerPort,
                platform = "macOS", operatingSystem = "macOS")
            val deadline = System.currentTimeMillis() + 15 * 60_000
            while (!stop.exists() && System.currentTimeMillis() < deadline) {
                runOnMainSync {
                    val viewModel = getter.invoke(activity) as HomeViewModel
                    viewModel.onDeviceFound(peer)
                    if (viewModel.uiState.value.pendingTransferRequest?.senderId == peerId) {
                        when (decision) {
                            "accept" -> viewModel.acceptTransfer(false)
                            "decline" -> viewModel.declineTransfer()
                        }
                    }
                }
                Thread.sleep(250)
            }
            stop.delete()
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "Interop fixture stopped") })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", error.toString()) })
        }
    }
}
