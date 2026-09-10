// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.nena

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Search over previously-uploaded watch logs. See [SearchViewModel] for the split between
 * the HTTP data fetch and the WebSocket command channel (refresh/stop) backing this screen.
 */
@Composable
fun SearchView(modifier: Modifier = Modifier) {
    val viewModel: SearchViewModel = viewModel()
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val locked by viewModel.locked.collectAsState()

    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (locked) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Search is temporarily disabled by the server.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateQuery(it) },
            label = { Text("Search uploaded logs") },
            singleLine = true,
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        )

        androidx.compose.material3.Button(
            onClick = { viewModel.search(query) },
            enabled = !locked,
        ) {
            Text("Search")
        }

        when (val state = results) {
            is NenaUiState.Loading -> CircularProgressIndicator()
            is NenaUiState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is NenaUiState.Success -> {
                if (state.data.isEmpty()) {
                    Text("No matches.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.data) { result ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "${result.deviceModel} - ${result.source}",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(result.sessionId, style = MaterialTheme.typography.bodySmall)
                                    Text(result.snippet, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            is NenaUiState.Idle -> Unit
        }
    }
}
