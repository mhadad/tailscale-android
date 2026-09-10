// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.nena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.view.SettingsView
import com.tailscale.ipn.ui.view.TintedSwitch
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.SettingsNav

private enum class NenaTab(val label: String) {
    HOME("Home"),
    ACTIVITY("Activity"),
    PAYMENTS("Payments"),
    SETTINGS("Settings"),
}

/**
 * The app's actual launch destination: "Pull watch logs" is the home tab's content, with the
 * VPN connect toggle and a watch-discovery refresh action both promoted into the top bar
 * (rather than buried in the old peer-list-first MainView), plus a persistent bottom nav for
 * Home / Activity / Payments / Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NenaHomeScaffold(
    mainViewModel: MainViewModel,
    settingsNav: SettingsNav,
    appViewModel: AppViewModel,
) {
    var selectedTab by rememberSaveable { mutableStateOf(NenaTab.HOME) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val watchLogsViewModel: WatchLogsViewModel = viewModel()
    val isOn by mainViewModel.vpnToggleState.collectAsState(initial = false)
    val disableToggle = !mainViewModel.isToggleInProgress.value
    val discoveryState by watchLogsViewModel.discoveryState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Compact VPN connect toggle, promoted from MainView's header into the
                    // corner here - Home is now the watch-bridge screen, not the peer list,
                    // so there's no room for (and no longer a need for) the full account/
                    // netmap header row that used to live above it.
                    TintedSwitch(
                        checked = isOn,
                        enabled = disableToggle,
                        onCheckedChange = { desired -> mainViewModel.toggleVpn(desired) },
                    )
                },
                title = { Text(if (searchActive) "Search" else selectedTab.label) },
                actions = {
                    if (selectedTab == NenaTab.HOME) {
                        // Search: fetches over HTTP, but stays connected to a WebSocket for
                        // as long as it's open purely to receive server-pushed "refresh"/
                        // "stop" commands - see SearchViewModel.
                        IconButton(onClick = { searchActive = !searchActive }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { watchLogsViewModel.scanForNearbyWatches() }) {
                            if (discoveryState is NenaUiState.Loading) {
                                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            } else {
                                Text("⟳", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == NenaTab.HOME,
                    onClick = { selectedTab = NenaTab.HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") },
                )
                NavigationBarItem(
                    selected = selectedTab == NenaTab.ACTIVITY,
                    onClick = { selectedTab = NenaTab.ACTIVITY },
                    icon = { Text("📋") }, // clipboard glyph - History isn't in this app's core icon set
                    label = { Text("Activity") },
                )
                NavigationBarItem(
                    selected = selectedTab == NenaTab.PAYMENTS,
                    onClick = { selectedTab = NenaTab.PAYMENTS },
                    icon = { Text("💳") }, // card glyph - CreditCard isn't in this app's core icon set
                    label = { Text("Payments") },
                )
                NavigationBarItem(
                    selected = selectedTab == NenaTab.SETTINGS,
                    onClick = { selectedTab = NenaTab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        when {
            selectedTab == NenaTab.HOME && searchActive -> SearchView(modifier = Modifier.padding(padding))
            selectedTab == NenaTab.HOME -> WatchLogsContent(
                viewModel = watchLogsViewModel,
                modifier = Modifier.padding(padding),
                showDiscoveryHeader = false,
            )
            selectedTab == NenaTab.ACTIVITY -> PlaceholderTabContent(
                modifier = Modifier.padding(padding),
                message = "Past pull/upload sessions will show up here.",
            )
            selectedTab == NenaTab.PAYMENTS -> PlaceholderTabContent(
                modifier = Modifier.padding(padding),
                message = "Nothing to show yet.",
            )
            // Settings keeps using the existing full SettingsView/nav graph as-is (bug report,
            // DNS, tailnet lock, etc. all still reachable from within it).
            else -> Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                SettingsView(settingsNav = settingsNav, appViewModel = appViewModel)
            }
        }
    }
}

@Composable
private fun PlaceholderTabContent(modifier: Modifier = Modifier, message: String) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
