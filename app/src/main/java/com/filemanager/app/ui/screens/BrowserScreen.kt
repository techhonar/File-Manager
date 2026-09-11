package com.filemanager.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.FileRow
import com.filemanager.app.ui.components.TextInputDialog
import com.filemanager.app.viewmodel.BrowserViewModel
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions
import androidx.compose.runtime.collectAsState

/**
 * The folder browser: breadcrumbs, a file list, and a contextual action bar
 * that replaces the toolbar while items are selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()
    val message by viewModel.messages.collectAsState()
    val snackbarState = remember { SnackbarHostState() }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        topBar = {
            if (state.inSelectionMode) {
                SelectionBar(
                    count = state.selected.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onCopy = viewModel::copy,
                    onCut = viewModel::cut,
                    onDelete = viewModel::deleteSelected,
                    onCompress = viewModel::compressSelected,
                    // Renaming two files at once is meaningless, so the action
                    // only appears for a single selection.
                    onRename = state.selected.singleOrNull()?.let { path ->
                        { renameTarget = state.entries.firstOrNull { it.path == path } }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = state.path.substringAfterLast('/').ifEmpty { "Storage" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (!viewModel.navigateUp()) onNavigateBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                        }
                    },
                    actions = {
                        if (clipboard != null) {
                            IconButton(onClick = viewModel::paste) {
                                Icon(Icons.Default.ContentPaste, "Paste")
                            }
                        }
                        IconButton(onClick = { showNewFolderDialog = true }) {
                            Icon(Icons.Default.CreateNewFolder, "New folder")
                        }
                        SortMenu(current = state.sort, onSelect = viewModel::setSort)
                        OverflowMenu(
                            showHidden = state.showHidden,
                            onToggleHidden = viewModel::toggleHidden,
                            onToggleGrid = viewModel::toggleGrid,
                        )
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(crumbs = state.breadcrumbs, onNavigate = viewModel::load)
            HorizontalDivider()

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        text = state.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(32.dp),
                    )
                }

                state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("This folder is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(contentPadding = contentPadding) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            isSelected = entry.path in state.selected,
                            selectionMode = state.inSelectionMode,
                            onClick = {
                                when {
                                    state.inSelectionMode -> viewModel.toggleSelection(entry.path)
                                    entry.isDir -> viewModel.load(entry.path)
                                    // Tapping a zip extracts it in place --
                                    // the one file type this app opens itself.
                                    entry.category == FileCategory.ARCHIVE ->
                                        viewModel.extract(entry.path)
                                    else -> onOpenFile(entry)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(entry.path) },
                        )
                    }
                }
            }
        }
    }

    if (showNewFolderDialog) {
        TextInputDialog(
            title = "New folder",
            label = "Folder name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createFolder(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename",
            label = "New name",
            initial = target.name,
            confirmLabel = "Rename",
            onConfirm = { name ->
                viewModel.rename(target.path, name)
                viewModel.clearSelection()
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/** Horizontally scrolling path, each segment tappable. */
@Composable
private fun Breadcrumbs(crumbs: List<Pair<String, String>>, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        crumbs.forEachIndexed { index, (label, path) ->
            if (index > 0) {
                Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (index == crumbs.lastIndex) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.clickable { onNavigate(path) }.padding(4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onCompress: () -> Unit,
    onRename: (() -> Unit)?,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel selection")
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, "Select all") }
            onRename?.let { rename ->
                IconButton(onClick = rename) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
            }
            IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Default.ContentCut, "Move") }
            IconButton(onClick = onCompress) { Icon(Icons.Default.FolderZip, "Compress") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        },
    )
}

@Composable
private fun SortMenu(current: SortOptions, onSelect: (SortOptions) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.Sort, "Sort") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            SortKey.NAME to "Name",
            SortKey.SIZE to "Size",
            SortKey.MODIFIED to "Date modified",
            SortKey.TYPE to "Type",
        ).forEach { (key, label) ->
            DropdownMenuItem(
                text = {
                    // Tapping the active key flips direction, which is the
                    // convention users expect from a sort menu.
                    val arrow = if (current.key == key) {
                        if (current.descending) " ↓" else " ↑"
                    } else {
                        ""
                    }
                    Text(label + arrow)
                },
                onClick = {
                    val descending = if (current.key == key) !current.descending else false
                    onSelect(current.copy(key = key, descending = descending))
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onToggleGrid: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "More") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
            onClick = { onToggleHidden(); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("Toggle grid view") },
            onClick = { onToggleGrid(); expanded = false },
        )
    }
}
