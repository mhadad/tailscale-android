// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.nena

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "NenaSearchViewModel"

@Serializable
private data class SearchResult(
    val sessionId: String,
    val deviceModel: String,
    val source: String,
    val capturedAtEpochMillis: Long,
    val snippet: String,
)

@Serializable
private data class SearchResponse(val results: List<SearchResult>)

data class NenaSearchResult(
    val sessionId: String,
    val deviceModel: String,
    val source: String,
    val snippet: String,
)

/**
 * Search over previously-uploaded watch logs, backed by agent-service.
 *
 * Two separate channels, deliberately: a plain HTTP GET does the actual data fetch (request
 * in, results back, done - cacheable, no connection to hold open just to ask a question), while
 * a WebSocket stays connected the whole time this screen is open purely to receive server-
 * pushed *commands*: "refresh" (re-run the last query - e.g. new data landed server-side) or
 * "stop" (disable the search UI entirely, e.g. for maintenance). The server never expects
 * anything meaningful back over that socket; it's push-only.
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = application.getSharedPreferences("nena_watch_bridge", Context.MODE_PRIVATE)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<NenaUiState<List<NenaSearchResult>>>(NenaUiState.Idle)
    val results: StateFlow<NenaUiState<List<NenaSearchResult>>> = _results

    // Set by a "stop" command pushed from the server - disables further searching until a
    // "refresh" (or a fresh screen open) clears it.
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked

    private var webSocket: WebSocket? = null

    init {
        connectCommandStream()
    }

    private fun agentBaseUrl(): String = prefs.getString("agent_base_url", "").orEmpty()

    private fun connectCommandStream() {
        val base = agentBaseUrl()
        if (base.isBlank()) return
        val wsUrl = base.trim().replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") +
            "/v1/search/stream"
        val request = Request.Builder().url(wsUrl).build()
        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "command received: $text")
                when (text.trim().lowercase()) {
                    "refresh" -> search(_query.value)
                    "stop" -> _locked.value = true
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "command stream failed", t)
            }
        })
    }

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun search(query: String) {
        if (_locked.value) return
        val base = agentBaseUrl()
        if (base.isBlank()) {
            _results.value = NenaUiState.Error("agent-service URL not configured")
            return
        }
        viewModelScope.launch {
            _results.value = NenaUiState.Loading
            _results.value = runCatching {
                withContext(Dispatchers.IO) {
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    val request = Request.Builder().url("${base.trimEnd('/')}/v1/search?q=$encoded").get().build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) throw java.io.IOException("Search failed: HTTP ${response.code}")
                        json.decodeFromString<SearchResponse>(body).results.map {
                            NenaSearchResult(it.sessionId, it.deviceModel, it.source, it.snippet)
                        }
                    }
                }
            }.fold(
                onSuccess = { NenaUiState.Success(it) },
                onFailure = { NenaUiState.Error(it.message ?: "Search failed") },
            )
        }
    }

    override fun onCleared() {
        webSocket?.close(1000, "screen closed")
        webSocket = null
        super.onCleared()
    }
}
