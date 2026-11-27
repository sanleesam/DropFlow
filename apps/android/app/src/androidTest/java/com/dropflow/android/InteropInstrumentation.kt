package com.dropflow.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
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
 * Optional: `sendDir` sends every file in that folder under the app's files
 * directory to the peer once, and `cancelAfterMs` cancels a transfer that long
 * after its progress appears. Both go through the normal ViewModel methods.
 * `directLink on|off` sets the app's own Direct link preference first.
 * Never packaged in a release APK.
 */
class InteropInstrumentation : Instrumentation() {
    private var peerPort = 0
    private lateinit var peerHost: String
    private lateinit var peerId: String
    private var decision = "manual"
    private var sendDir: String? = null
    private var cancelAfterMs = -1L
    private var directLink: Boolean? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        peerPort = arguments?.getString("peerPort")?.toIntOrNull() ?: 0
        peerHost = arguments?.getString("peerHost") ?: "10.0.2.2"
        peerId = arguments?.getString("peerId") ?: "mac-interop-test"
        decision = arguments?.getString("decision") ?: "manual"
        sendDir = arguments?.getString("sendDir")
        cancelAfterMs = arguments?.getString("cancelAfterMs")?.toLongOrNull() ?: -1L
        directLink = arguments?.getString("directLink")?.let { it == "on" }
        start()
    }

    override fun onStart() {
        try {
            require(peerPort in 1..65535) { "peerPort is required" }
            val stop = File(targetContext.filesDir, "stop-interop-fixture")
            stop.delete()
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            directLink?.let { enabled ->
                val app = targetContext.applicationContext as DropFlowApplication
                kotlinx.coroutines.runBlocking { app.userPreferencesRepository.updateDirectLink(enabled) }
                // The app applies its preferences a moment after they are saved.
                Thread.sleep(1_000)
            }
            val getter = MainActivity::class.java.getDeclaredMethod("getHomeViewModel").apply { isAccessible = true }
            val peer = Device(id = peerId, name = "Mac app · direct emulator test", type = DeviceType.DESKTOP,
                addresses = listOf(DeviceAddress(peerHost, "ipv4")), port = peerPort,
                platform = "macOS", operatingSystem = "macOS")
            val deadline = System.currentTimeMillis() + 15 * 60_000
            var activeSince = 0L
            while (!stop.exists() && System.currentTimeMillis() < deadline) {
                runOnMainSync {
                    val viewModel = getter.invoke(activity) as HomeViewModel
                    viewModel.onDeviceFound(peer)
                    val request = viewModel.uiState.value.pendingTransferRequest
                    if (request != null && request.senderId == peerId) {
                        when (decision) {
                            "accept" -> viewModel.acceptTransfer(request)
                            "decline" -> viewModel.declineTransfer(request)
                        }
                    }
                    sendDir?.let { name ->
                        sendDir = null
                        val files = File(targetContext.filesDir, name).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
                        if (viewModel.uiState.value.selectedDeviceId != peerId) viewModel.selectDevice(peerId)
                        viewModel.sendFiles(files.map(Uri::fromFile))
                    }
                    if (cancelAfterMs >= 0 && viewModel.uiState.value.activeProgress != null) {
                        val now = System.currentTimeMillis()
                        if (activeSince == 0L) activeSince = now
                        if (now - activeSince >= cancelAfterMs) {
                            cancelAfterMs = -1
                            viewModel.cancelActiveTransfer()
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
