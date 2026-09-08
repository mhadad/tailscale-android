// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.nena

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "NenaWatchLogsViewModel"

sealed class NenaUiState<out T> {
    data object Idle : NenaUiState<Nothing>()
    data object Loading : NenaUiState<Nothing>()
    data class Success<T>(val data: T) : NenaUiState<T>()
    data class Error(val message: String) : NenaUiState<Nothing>()
}

/** Wire format matching Nena's shared-protocol LogUploadRequest exactly - kept as a plain local
 * data class since this is a standalone project rather than a KMP module sharing that type. */
@Serializable
private data class LogUploadRequest(
    val sessionId: String,
    val deviceSerial: String,
    val deviceModel: String,
    val androidVersion: String,
    val source: String, // "LOGCAT" | "BUGREPORT"
    val capturedAtEpochMillis: Long,
    val content: String,
)

class WatchLogsViewModel(application: Application) : AndroidViewModel(application) {
    private val adb = AdbBridge(application)
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val _pairState = MutableStateFlow<NenaUiState<Unit>>(NenaUiState.Idle)
    val pairState: StateFlow<NenaUiState<Unit>> = _pairState

    private val _connectState = MutableStateFlow<NenaUiState<Unit>>(NenaUiState.Idle)
    val connectState: StateFlow<NenaUiState<Unit>> = _connectState

    private val _uploadState = MutableStateFlow<NenaUiState<String>>(NenaUiState.Idle)
    val uploadState: StateFlow<NenaUiState<String>> = _uploadState

    private val _discoveryState =
        MutableStateFlow<NenaUiState<List<AdbBridge.DiscoveredService>>>(NenaUiState.Idle)
    val discoveryState: StateFlow<NenaUiState<List<AdbBridge.DiscoveredService>>> = _discoveryState

    // Android silently drops incoming multicast packets (which is how mDNS discovery
    // works) unless the app explicitly holds this lock - without it, our mdnsd daemon
    // would never see any broadcasts at all, no matter how close or how well-configured
    // the watch is.
    private val multicastLock: WifiManager.MulticastLock =
        (application.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("nena-watch-discovery")
            .apply { setReferenceCounted(true) }

    /** Whether our own `pauseVpnForSession()` actually turned the VPN off (so we know to turn it
     * back on later - if it was already off, e.g. user isn't logged into Tailscale, leave it alone). */
    private var vpnWasRunningBeforePause = false

    init {
        multicastLock.acquire()
        viewModelScope.launch {
            pauseVpnForSession()
            adb.startMdnsDaemon()
            // Keep adb's own server alive as a persistent background process for the whole
            // session - a one-shot invocation asks whatever server already exists for its
            // *current* results near-instantly (confirmed: ~15-20ms round trip), which is far
            // too fast for real mDNS discovery to have received anything yet if the server
            // was just auto-spawned fresh for that single call. Starting it explicitly and
            // giving it time to run in the background is what lets it actually accumulate
            // discovered services before we ask.
            adb.startAdbServer()

            // Give the servers a real moment to come up and start receiving broadcasts before
            // asking for results - much longer than a bare "just started" race, since mDNS
            // discovery itself needs at least one real query/response round trip.
            kotlinx.coroutines.delay(2_000)
            scanForNearbyWatches()
            kotlinx.coroutines.delay(3_000)
            scanForNearbyWatches()
        }
    }

    override fun onCleared() {
        if (multicastLock.isHeld) multicastLock.release()
        adb.stopAdbServer()
        adb.stopMdnsDaemon()
        // Safety net: never leave the VPN stuck paused just because the user navigated away
        // without uploading.
        viewModelScope.launch { resumeVpnIfNeeded() }
        super.onCleared()
    }

    /**
     * Our embedded Tailscale VPN captures this app's own traffic - including our `adb`/`mdnsd`
     * subprocesses, since `ConnectivityManager.bindProcessToNetwork` only affects sockets
     * created directly by this JVM process, not natively `exec`'d children (confirmed
     * empirically). So for the whole time this screen is open, we pause the VPN entirely,
     * letting local pair/connect/discovery/pull traffic reach the watch directly - the
     * upload step needs Tailscale again to reach the private agent-service, so it resumes
     * the VPN itself right before making that call.
     */
    private suspend fun pauseVpnForSession() {
        vpnWasRunningBeforePause = Notifier.state.value == Ipn.State.Running
        if (vpnWasRunningBeforePause) {
            Log.d(TAG, "pausing VPN for watch-logs session")
            App.get().stopVPN()
            withTimeoutOrNull(5_000) { Notifier.state.first { it != Ipn.State.Running } }
        }
    }

    private suspend fun resumeVpnIfNeeded() {
        if (vpnWasRunningBeforePause && Notifier.state.value != Ipn.State.Running) {
            Log.d(TAG, "resuming VPN")
            App.get().startVPN()
            withTimeoutOrNull(10_000) { Notifier.state.first { it == Ipn.State.Running } }
        }
    }

    /** Scans for watches currently broadcasting wireless-debugging mDNS services nearby. */
    fun scanForNearbyWatches() {
        viewModelScope.launch {
            _discoveryState.value = NenaUiState.Loading
            _discoveryState.value = runCatching { adb.discoverServices() }.fold(
                onSuccess = { NenaUiState.Success(it) },
                onFailure = { NenaUiState.Error(it.message ?: "Scan failed") },
            )
        }
    }

    private var deviceAddress: String? = null

    fun pair(host: String, port: Int, pairingCode: String) {
        viewModelScope.launch {
            _pairState.value = NenaUiState.Loading
            _pairState.value = when (val result = adb.pair(host, port, pairingCode)) {
                is AdbBridge.PairResult.Success -> NenaUiState.Success(Unit)
                is AdbBridge.PairResult.Failure -> NenaUiState.Error(result.message)
            }
        }
    }

    fun connect(host: String, port: Int) {
        viewModelScope.launch {
            _connectState.value = NenaUiState.Loading
            _connectState.value = when (val result = adb.connect(host, port)) {
                is AdbBridge.ConnectResult.Success -> {
                    deviceAddress = result.deviceAddress
                    NenaUiState.Success(Unit)
                }
                is AdbBridge.ConnectResult.Failure -> NenaUiState.Error(result.message)
            }
        }
    }

    /** Pulls bugreport + logcat from the connected watch and uploads both to [agentBaseUrl]. */
    fun pullAndUpload(agentBaseUrl: String) {
        val address = deviceAddress
        if (address == null) {
            _uploadState.value = NenaUiState.Error("Connect to the watch first")
            return
        }
        viewModelScope.launch {
            _uploadState.value = NenaUiState.Loading
            _uploadState.value = runCatching {
                withContext(Dispatchers.IO) {
                    val sessionId = UUID.randomUUID().toString()

                    // Pull everything from the watch first, while the VPN is still paused
                    // for this session, then resume it before uploading - agent-service is
                    // only reachable through Tailscale.
                    val model = adb.deviceModel(address)
                    val version = adb.androidVersion(address)
                    val bugreport = adb.pullBugreport(address)
                    val logcat = adb.pullLogcat(address)

                    resumeVpnIfNeeded()

                    uploadLog(agentBaseUrl, sessionId, address, model, version, "BUGREPORT", bugreport)
                    uploadLog(agentBaseUrl, sessionId, address, model, version, "LOGCAT", logcat)

                    sessionId
                }
            }.fold(
                onSuccess = { sessionId -> NenaUiState.Success("Uploaded (session $sessionId)") },
                onFailure = { NenaUiState.Error(it.message ?: "Upload failed") },
            )
        }
    }

    private fun uploadLog(
        agentBaseUrl: String,
        sessionId: String,
        deviceSerial: String,
        deviceModel: String,
        androidVersion: String,
        source: String,
        content: String,
    ) {
        val request = LogUploadRequest(
            sessionId = sessionId,
            deviceSerial = deviceSerial,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
            source = source,
            capturedAtEpochMillis = System.currentTimeMillis(),
            content = content,
        )
        val body = json.encodeToString(request).toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("${agentBaseUrl.trimEnd('/')}/v1/logs")
            .post(body)
            .build()
        http.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("Upload failed for $source: HTTP ${response.code}")
            }
        }
    }
}
