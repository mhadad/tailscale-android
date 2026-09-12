// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.nena

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Standalone screen wrapper (reachable from Settings -> "Pull watch logs") around
 * [WatchLogsContent] - the same content now also embedded directly as the Home tab's
 * body (see [com.tailscale.ipn.nena.HomeView]), which owns its own top bar (VPN toggle
 * + refresh) instead of this screen's back-button app bar.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WatchLogsView(onNavigateBack: () -> Unit) {
    val viewModel: WatchLogsViewModel = viewModel()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pull watch logs") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        WatchLogsContent(viewModel = viewModel, modifier = Modifier.padding(padding), showDiscoveryHeader = true)
    }
}

/**
 * The actual pairing/connect/stream-logs UI, extracted so it can be embedded either inside
 * [WatchLogsView]'s own screen or directly as [HomeView]'s body. When [showDiscoveryHeader] is
 * false (the Home tab case), the "Nearby watches" card's own refresh button is omitted since
 * Home's top bar already has one wired to the same [WatchLogsViewModel.scanForNearbyWatches].
 */
@Composable
fun WatchLogsContent(
    viewModel: WatchLogsViewModel,
    modifier: Modifier = Modifier,
    showDiscoveryHeader: Boolean = false,
) {
    val pairState by viewModel.pairState.collectAsState()
    val connectState by viewModel.connectState.collectAsState()
    val uploadState by viewModel.uploadState.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val agentBaseUrl by viewModel.agentBaseUrl.collectAsState()

    var host by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }

    // Auto-fill from the first discovered watch as soon as a scan finds one, instead of
    // requiring a manual tap on the "Nearby watches" list - there's consistently been only
    // one watch on this network, so this is the common case, not an edge case. A tap on a
    // specific row (below) still works too, e.g. to switch to a different watch.
    //
    // The pairing port (and its code) are single-use and rotate every time the watch's own
    // "Pair new device" screen is reopened - unlike the IP and the stable always-on connect
    // port, which only need filling once, the pairing port must always track the *latest*
    // scan result, or pairing will keep failing against a port the watch has already moved
    // on from (confirmed empirically: repeated "protocol fault"/"Connection refused" pair
    // failures against a stale port after the watch generated a fresh one).
    LaunchedEffect(discoveryState) {
        val state = discoveryState
        if (state is NenaUiState.Success) {
            val relevant = state.data.filter { it.kind != AdbBridge.ServiceKind.OTHER }
            val targetHost = (if (host.isBlank()) relevant.firstOrNull()?.host else host)
                ?: return@LaunchedEffect
            val servicesAtHost = relevant.filter { it.host == targetHost }
            if (host.isBlank()) {
                host = targetHost
                servicesAtHost.firstOrNull { it.kind == AdbBridge.ServiceKind.CONNECT }
                    ?.let { connectPort = it.port.toString() }
            }
            servicesAtHost.firstOrNull { it.kind == AdbBridge.ServiceKind.PAIRING }
                ?.let { pairPort = it.port.toString() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            "Connects to a watch on this phone's local network (e.g. its own hotspot), " +
                "pulls a bugreport + logcat, and uploads them to your agent-service - reachable " +
                "here through Tailscale, even on a different network/region.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Nearby watches", style = MaterialTheme.typography.titleSmall)
                    if (showDiscoveryHeader) {
                        IconButton(onClick = { viewModel.scanForNearbyWatches() }) {
                            if (discoveryState is NenaUiState.Loading) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            } else {
                                Text("⟳", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                when (val state = discoveryState) {
                    is NenaUiState.Success -> {
                        // Group by IP: a watch usually broadcasts its pairing and connect
                        // services simultaneously while "Pair new device" is open, so one
                        // tap can fill the IP and both ports at once - only the code stays
                        // manual, since that's the actual security factor.
                        val byHost = state.data
                            .filter { it.kind != AdbBridge.ServiceKind.OTHER }
                            .groupBy { it.host }
                        if (byHost.isEmpty()) {
                            Text(
                                "No watches found yet. Make sure wireless debugging is on and " +
                                    "you're on the same network, then tap refresh.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            byHost.forEach { (candidateHost, servicesAtHost) ->
                                val pairing = servicesAtHost.firstOrNull { it.kind == AdbBridge.ServiceKind.PAIRING }
                                val connect = servicesAtHost.firstOrNull { it.kind == AdbBridge.ServiceKind.CONNECT }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            host = candidateHost
                                            pairing?.let { pairPort = it.port.toString() }
                                            connect?.let { connectPort = it.port.toString() }
                                        }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column {
                                        Text(
                                            pairing?.name ?: connect?.name ?: candidateHost,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(candidateHost, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                        if (pairing != null) {
                                            Text(
                                                "Pairing :${pairing.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (connect != null) {
                                            Text(
                                                "Connect :${connect.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    is NenaUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Watch IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Step 1 - Pair", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = pairPort,
                        onValueChange = { if (it.all(Char::isDigit)) pairPort = it },
                        label = { Text("Pairing port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pairCode,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pairCode = it },
                        label = { Text("Pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                StatusRow(
                    state = pairState,
                    buttonLabel = "Pair",
                    enabled = host.isNotBlank() && pairPort.toIntOrNull() != null && pairCode.length == 6,
                    onClick = { viewModel.pair(host.trim(), pairPort.toInt(), pairCode) },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Step 2 - Connect", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { if (it.all(Char::isDigit)) connectPort = it },
                    label = { Text("Debugging port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                StatusRow(
                    state = connectState,
                    buttonLabel = "Connect",
                    // Guarded on a successful pair first - `adb connect` against a device
                    // this session hasn't paired with yet either hangs waiting on a TLS
                    // handshake the watch never completes, or fails outright, and either
                    // way it's confusing without the pairing step's own clearer error
                    // surfaced first. Step order in the UI (pair above, connect below)
                    // is now also enforced, not just suggested.
                    enabled = pairState is NenaUiState.Success && host.isNotBlank() && connectPort.toIntOrNull() != null,
                    onClick = { viewModel.connect(host.trim(), connectPort.toInt()) },
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The agent-service URL field itself is hidden - it's persisted (see
                // WatchLogsViewModel.agentBaseUrl) and defaults to this deployment's
                // known Tailscale address, so there's nothing left for the user to
                // fill in here; just the action.
                StatusRow(
                    state = uploadState,
                    buttonLabel = "Stream logs",
                    enabled = connectState is NenaUiState.Success && agentBaseUrl.isNotBlank(),
                    onClick = { viewModel.pullAndUpload(agentBaseUrl.trim()) },
                )
            }
        }
    }
}

@Composable
private fun <T> StatusRow(
    state: NenaUiState<T>,
    buttonLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onClick, enabled = enabled && state !is NenaUiState.Loading) {
            Text(buttonLabel)
        }
        when (state) {
            is NenaUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            is NenaUiState.Success -> Text("✓ Success", color = MaterialTheme.colorScheme.primary)
            is NenaUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is NenaUiState.Idle -> Unit
        }
    }
}
