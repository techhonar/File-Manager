package com.filemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.remote.RemoteEntry
import com.filemanager.app.ui.components.AnimatedBottomBar
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.TextInputDialog
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.RemoteBrowserViewModel
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Browsing one network server.
 *
 * Reads plainer than the local browser on purpose. There is no trash on a
 * remote server, no thumbnails worth fetching a whole file for, and no sort or
 * layout options - every one of those is either a round trip per row or a
 * promise the protocol cannot keep.
 */
@Composable
fun RemoteBrowserScreen(
    viewModel: RemoteBrowserViewModel,
    /** Hands a fetched file to another app. */
    onOpenDownloaded: (File) -> Unit,
    /** Where Download puts things. */
    downloadDirectory: File,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()
    val clipboard by viewModel.clipboardContents.collectAsState()
    val openRequest by viewModel.openRequest.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<RemoteEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(openRequest) {
        openRequest?.let {
            onOpenDownloaded(it)
            viewModel.consumeOpenRequest()
        }
    }

    BackHandler(enabled = true) {
        if (!viewModel.navigateUp()) onNavigateBack()
    }

    OneUiScreen(
        title = if (state.inSelectionMode) {
            "${state.selected.size} selected"
        } else {
            state.server.label
        },
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            IconButton(
                onClick = { if (!viewModel.navigateUp()) onNavigateBack() },
            ) {
                Icon(
                    if (state.inSelectionMode) Icons.Default.Close
                    else Icons.AutoMirrored.Filled.ArrowBack,
                    if (state.inSelectionMode) "Cancel selection" else "Up",
                )
            }
        },
        actions = {
            if (state.inSelectionMode) {
                SelectAllToggle(
                    allSelected = state.allSelected,
                    onToggle = viewModel::toggleSelectAll,
                )
                val single = state.selectedEntries.singleOrNull()
                IconButton(onClick = { renameTarget = single }, enabled = single != null) {
                    Icon(Icons.Default.DriveFileRenameOutline, "Rename")
                }
            } else {
                if (clipboard != null) {
                    IconButton(onClick = viewModel::pasteFromClipboard) {
                        Icon(Icons.Default.Upload, "Upload copied files")
                    }
                }
                IconButton(onClick = { showNewFolder = true }) {
                    Icon(Icons.Default.CreateNewFolder, "New folder")
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                if (state.entries.isNotEmpty()) {
                    IconButton(onClick = viewModel::enterSelectionMode) {
                        Icon(Icons.Default.SelectAll, "Select items")
                    }
                }
            }
        },
        bottomBar = {
            AnimatedBottomBar(visible = state.inSelectionMode) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            TextButton(
                                onClick = { viewModel.downloadSelection(downloadDirectory) },
                            ) {
                                Icon(Icons.Default.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Download")
                            }
                            TextButton(onClick = viewModel::deleteSelection) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Text(
                text = state.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    horizontal = OneUi.ScreenPadding,
                    vertical = 4.dp,
                ),
            )

            // A transfer says what it is doing rather than freezing the list,
            // which over a slow link is the difference between working and
            // broken.
            state.busy?.let { busy ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = busy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            Crossfade(
                targetState = when {
                    state.isLoading -> Pane.LOADING
                    state.error != null -> Pane.ERROR
                    state.entries.isEmpty() -> Pane.EMPTY
                    else -> Pane.ITEMS
                },
                animationSpec = PaneFade,
                label = "folder",
            ) { pane ->
                when (pane) {
                    Pane.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    Pane.ERROR -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                            )
                            TextButton(onClick = viewModel::refresh) { Text("Try again") }
                        }
                    }

                    Pane.EMPTY -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            text = "This folder is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Pane.ITEMS -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                    ) {
                        items(state.entries, key = { it.path }) { entry ->
                            Box(Modifier.animateItem()) {
                                RemoteRow(
                                    entry = entry,
                                    isSelected = entry.path in state.selected,
                                    selectionMode = state.inSelectionMode,
                                    onClick = {
                                        if (state.inSelectionMode) {
                                            viewModel.toggleSelection(entry.path)
                                        } else {
                                            viewModel.open(entry)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(entry.path) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            title = "New folder",
            label = "Folder name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = {
                viewModel.createFolder(it)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename",
            label = "New name",
            initial = target.name,
            confirmLabel = "Rename",
            onConfirm = {
                viewModel.rename(target, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteRow(
    entry: RemoteEntry,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = OneUi.RowHeight)
            .padding(horizontal = OneUi.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDir) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Date and size, or nothing rather than a wrong date.
 *
 * Several servers report no modification time at all - FTP in particular, when
 * its listing format does not carry one - and rendering that zero would date
 * every file to 1970.
 */
private fun RemoteEntry.subtitle(): String {
    val when_ = if (modifiedMs > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(modifiedMs))
    } else {
        null
    }
    val what = if (isDir) null else humanSize(size)
    return listOfNotNull(when_, what).joinToString("  ·  ")
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format("%.1f %s", value, units[unit])
}
