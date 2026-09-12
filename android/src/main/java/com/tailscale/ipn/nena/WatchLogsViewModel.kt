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
private const val KEY_AGENT_BASE_URL = "agent_base_url"
// Nena: this Linux machine's Tailscale IP, running agent-service on :8080 - only used as
// the seed value the very first time (before anything's been saved to prefs).
private const val DEFAULT_AGENT_BASE_URL = "http://100.125.117.100:8080"

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

    // Persists the agent-service URL across sessions - it's a fixed property of wherever
    // agent-service is deployed (this machine's Tailscale IP/hostname), not something that
    // should need retyping every time this screen is reopened.
    private val prefs = application.getSharedPreferences("nena_watch_bridge", Context.MODE_PRIVATE)
    private val _agentBaseUrl =
        MutableStateFlow(prefs.getString(KEY_AGENT_BASE_URL, DEFAULT_AGENT_BASE_URL) ?: DEFAULT_AGENT_BASE_URL)
    val agentBaseUrl: StateFlow<String> = _agentBaseUrl

    fun updateAgentBaseUrl(url: String) {
        _agentBaseUrl.value = url
        prefs.edit().putString(KEY_AGENT_BASE_URL, url).apply()
    }

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

            // Keep re-scanning periodically for as long as this screen is open, rather than
            // a one-shot pair of scans - the pairing port/code the watch broadcasts rotates
            // every time its own "Pair new device" screen is reopened, so a single scan can
            // go stale (confirmed empirically: pairing kept failing against a port the watch
            // had already moved on from). This keeps the auto-filled pairing port current
            // without the user needing to keep tapping refresh manually.
            while (true) {
                scanForNearbyWatches()
                kotlinx.coroutines.delay(5_000)
            }
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
            val reachedRunning = withTimeoutOrNull(10_000) { Notifier.state.first { it == Ipn.State.Running } }
            if (reachedRunning == null) {
                Log.w(TAG, "VPN did not reach Running within 10s of startVPN() - upload will likely fail to reach agent-service")
            } else {
                Log.d(TAG, "VPN resumed, now Running")
            }
        } else {
            Log.d(TAG, "VPN resume not needed (wasRunningBeforePause=$vpnWasRunningBeforePause, currentState=${Notifier.state.value})")
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
        // Defense-in-depth alongside the UI's own disabled-until-paired button: connecting
        // to a watch this session hasn't successfully paired with yet either hangs on a TLS
        // handshake the watch never completes, or fails outright - fail fast with a clear
        // message instead.
        if (pairState.value !is NenaUiState.Success) {
            _connectState.value = NenaUiState.Error("Pair with the watch first")
            return
        }
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

    /**
     * Pulls logcat from the connected watch and uploads it to [agentBaseUrl].
     *
     * Deliberately does NOT pull a full bugreport: `adb bugreport -` (streaming to stdout)
     * failed with "cannot create '-.zip': Read-only file system" - the local adb client
     * writes a real temp file before streaming rather than truly streaming to stdout, and
     * our process has no writable working directory set. Even fixed, a bugreport zip is
     * binary, but the upload path here (and agent-service's LogUploadRequest) treats
     * content as plain text - a real fix needs base64 (or multipart) support end to end,
     * not just a local file-path change. logcat is plain text and fits as-is, and is far
     * faster/smaller, which also matters given the intermittent Wi-Fi drops observed
     * between phone and watch (a multi-minute bugreport pull is much more likely to land
     * on a drop than a near-instant logcat dump).
     */
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
                    Log.d(TAG, "pullAndUpload starting: session=$sessionId device=$address agentBaseUrl=$agentBaseUrl")

                    // Pull everything from the watch first, while the VPN is still paused
                    // for this session, then resume it before uploading - agent-service is
                    // only reachable through Tailscale.
                    val model = adb.deviceModel(address)
                    val version = adb.androidVersion(address)
                    val logcat = adb.pullLogcat(address)
                    Log.d(TAG, "pulled from watch: model=$model androidVersion=$version logcatBytes=${logcat.length}")

                    resumeVpnIfNeeded()

                    uploadLog(agentBaseUrl, sessionId, address, model, version, "LOGCAT", logcat)
                    Log.d(TAG, "upload succeeded: session=$sessionId")

                    sessionId
                }
            }.fold(
                onSuccess = { sessionId -> NenaUiState.Success("Uploaded (session $sessionId)") },
                onFailure = { error ->
                    Log.e(TAG, "pullAndUpload failed", error)
                    NenaUiState.Error(error.message ?: "Upload failed")
                },
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
        val url = "${agentBaseUrl.trimEnd('/')}/v1/logs"
        val httpRequest = Request.Builder()
            .url(url)
            .post(body)
            .build()
        Log.d(TAG, "POST $url (session=$sessionId, source=$source, ${content.length} bytes)")
        try {
            http.newCall(httpRequest).execute().use { response ->
                Log.d(TAG, "POST $url -> HTTP ${response.code}")
                if (!response.isSuccessful) {
                    throw java.io.IOException("Upload failed for $source: HTTP ${response.code}")
                }
            }
        } catch (e: java.io.IOException) {
            // Covers both a non-2xx response (thrown above) and a transport-level failure
            // (e.g. UnknownHostException/ConnectException/SocketTimeoutException) - the
            // latter is the expected failure mode if the VPN isn't actually up by now,
            // since agentBaseUrl only resolves over Tailscale.
            Log.e(TAG, "POST $url failed", e)
            throw e
        }
    }
}
