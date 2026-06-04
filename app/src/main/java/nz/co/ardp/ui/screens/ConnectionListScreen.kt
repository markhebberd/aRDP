package nz.co.ardp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.co.ardp.connection.ConnectionConfig
import nz.co.ardp.ui.components.ConnectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListScreen(
    viewModel: ConnectionListViewModel,
    onConnect: (ConnectionConfig) -> Unit,
    onEdit: (ConnectionConfig) -> Unit,
    onAdd: () -> Unit,
) {
    val connections by viewModel.connections.collectAsState(initial = emptyList())
    var deleteTarget by remember { mutableStateOf<ConnectionConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("aRDP") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add connection")
            }
        },
    ) { padding ->
        if (connections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No connections yet.\nTap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(connections, key = { it.id }) { config ->
                    ConnectionCard(
                        config = config,
                        onClick = { onConnect(config) },
                        onEdit = { onEdit(config) },
                        onDelete = { deleteTarget = config },
                    )
                }
            }
        }

        deleteTarget?.let { config ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Delete connection?") },
                text = { Text("Delete \"${config.name.ifBlank { config.hostname }}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.delete(config.id)
                        deleteTarget = null
                    }) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}
