package com.filemanager.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.remote.RemoteType
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.RemoteServerDialog
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.RemoteServersViewModel

/** The saved network locations, and the form for adding one. */
@Composable
fun RemoteServersScreen(
    viewModel: RemoteServersViewModel,
    onOpenServer: (RemoteServer) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    OneUiScreen(
        title = "Network storage",
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(onClick = viewModel::addNew) {
                Icon(Icons.Default.Add, "Add storage")
            }
        },
    ) { padding ->
        if (state.servers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text(
                    text = "No network storage yet.\n\nAdd an FTP, SFTP, SMB or " +
                        "WebDAV server and it will appear here and on the home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                items(state.servers, key = { it.id }) { server ->
                    RemoteServerRow(
                        server = server,
                        onClick = { onOpenServer(server) },
                        onEdit = { viewModel.edit(server) },
                    )
                }
            }
        }
    }

    state.editing?.let { draft ->
        RemoteServerDialog(
            server = draft,
            testState = state.testState,
            testMessage = state.testMessage,
            onChange = viewModel::updateDraft,
            onTest = viewModel::test,
            onSave = viewModel::save,
            // Only an already-saved server can be deleted, and a new one is
            // the only one not in the list yet.
            onDelete = if (state.servers.any { it.id == draft.id }) {
                { viewModel.delete(draft) }
            } else {
                null
            },
            onDismiss = viewModel::cancelEdit,
        )
    }
}

@Composable
private fun RemoteServerRow(
    server: RemoteServer,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = OneUi.ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = server.type.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = server.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, "Edit ${server.label}")
        }
    }
}

/** A glyph per protocol, so the list is scannable without reading it. */
internal fun RemoteType.icon(): ImageVector = when (this) {
    RemoteType.FTP -> Icons.Default.Dns
    RemoteType.SFTP -> Icons.Default.Lan
    RemoteType.SMB -> Icons.Default.Folder
    RemoteType.WEBDAV -> Icons.Default.Cloud
}
