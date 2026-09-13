package com.filemanager.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.FileRow
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.SelectionActionBar
import com.filemanager.app.ui.components.TextInputDialog
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.BrowserViewModel
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions

/**
 * The folder browser.
 *
 * Two chrome states: normally a collapsing One UI title with the folder name;
 * in selection mode the title bar becomes a count and the actions move to a
 * bar along the bottom, which is where One UI puts them so they stay in thumb
 * reach on a tall phone.
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
    val clipboard by viewModel.clipboardContents.collectAsState()
    val message by viewModel.messages.collectAsState()
    val snackbarState = remember { SnackbarHostState() }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val folderName = state.path.substringAfterLast('/').ifEmpty { "Storage" }

    if (state.inSelectionMode) {
        // Selection mode gets a plain compact bar plus a bottom action bar.
        Scaffold(
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarState) },
            topBar = {
                TopAppBar(
                    title = { Text("${state.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, "Cancel selection")
                        }
                    },
                    actions = {
                        SelectAllToggle(
                            allSelected = state.allSelected,
                            onToggle = viewModel::toggleSelectAll,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                SelectionActionBar(
                    onCopy = viewModel::copy,
                    onMove = viewModel::cut,
                    onDelete = viewModel::deleteSelected,
                    onRename = state.selected.singleOrNull()?.let { path ->
                        { renameTarget = state.entries.firstOrNull { it.path == path } }
                    },
                    onCompress = viewModel::compressSelected,
                )
            },
        ) { padding ->
            FileList(state, viewModel, onOpenFile, padding, contentPadding)
        }
    } else {
        OneUiScreen(
            title = folderName,
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbarState) },
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
                CreateMenu(
                    onNewFolder = { showNewFolderDialog = true },
                    onNewFile = { showNewFileDialog = true },
                )
                SortMenu(current = state.sort, onSelect = viewModel::setSort)
                OverflowMenu(
                    showHidden = state.showHidden,
                    onToggleHidden = viewModel::toggleHidden,
                    onToggleGrid = viewModel::toggleGrid,
                    onSelectItems = viewModel::enterSelectionMode,
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Breadcrumbs(crumbs = state.breadcrumbs, onNavigate = viewModel::load)
                FileList(state, viewModel, onOpenFile, PaddingValues(0.dp), contentPadding)
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

    if (showNewFileDialog) {
        TextInputDialog(
            title = "New file",
            label = "File name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createFile(name)
                showNewFileDialog = false
            },
            onDismiss = { showNewFileDialog = false },
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

@Composable
private fun FileList(
    state: com.filemanager.app.viewmodel.BrowserState,
    viewModel: BrowserViewModel,
    onOpenFile: (FileEntry) -> Unit,
    scaffoldPadding: PaddingValues,
    contentPadding: PaddingValues,
) {
    when {
        state.isLoading -> Box(
            Modifier.padding(scaffoldPadding).fillMaxSize(),
            Alignment.Center,
        ) { CircularProgressIndicator() }

        state.error != null -> Box(
            Modifier.padding(scaffoldPadding).fillMaxSize(),
            Alignment.Center,
        ) {
            Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(32.dp),
            )
        }

        state.entries.isEmpty() -> Box(
            Modifier.padding(scaffoldPadding).fillMaxSize(),
            Alignment.Center,
        ) {
            Text(
                "This folder is empty",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> LazyColumn(
            modifier = Modifier.padding(scaffoldPadding).fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            items(state.entries, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    isSelected = entry.path in state.selected,
                    selectionMode = state.inSelectionMode,
                    onClick = {
                        when {
                            state.inSelectionMode -> viewModel.toggleSelection(entry.path)
                            entry.isDir -> viewModel.load(entry.path)
                            // Tapping a zip extracts it in place - the one
                            // file type this app opens itself.
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


/** Horizontally scrolling path, each segment tappable. */
@Composable
private fun Breadcrumbs(crumbs: List<Pair<String, String>>, onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = OneUi.ScreenPadding, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        crumbs.forEachIndexed { index, (label, path) ->
            if (index > 0) {
                Text(
                    "›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (index == crumbs.lastIndex) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onNavigate(path) }.padding(6.dp),
            )
        }
    }
}

/** The "+" action: a folder or an empty file. */
@Composable
private fun CreateMenu(
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.Add, "Create")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("New folder") },
            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
            onClick = { onNewFolder(); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("New file") },
            leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
            onClick = { onNewFile(); expanded = false },
        )
    }
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
                    // Tapping the active key flips direction, the convention
                    // users expect from a sort menu.
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
    onSelectItems: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, "More") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        // Selection could previously only be reached by long-pressing a file,
        // which left no way to simply select everything.
        DropdownMenuItem(
            text = { Text("Select items") },
            leadingIcon = { Icon(Icons.Default.SelectAll, null) },
            onClick = { onSelectItems(); expanded = false },
        )
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
