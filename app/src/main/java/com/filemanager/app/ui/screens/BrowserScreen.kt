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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import com.filemanager.app.ui.components.FileDetailRow
import com.filemanager.app.ui.components.FileGridCell
import com.filemanager.app.ui.components.DetailsDialog
import com.filemanager.app.viewmodel.ViewMode
import androidx.activity.compose.BackHandler
import com.filemanager.app.ui.components.ExtractDialog
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
    onShare: (List<String>) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()
    val clipboard by viewModel.clipboardContents.collectAsState()
    val message by viewModel.messages.collectAsState()
    val snackbarState = remember { SnackbarHostState() }

    // Hoisted above the selection-mode branch on purpose. That branch swaps a
    // Scaffold for a OneUiScreen, so the list below it is destroyed and rebuilt
    // - and a state remembered inside it went with it, dropping the user back
    // at the top the moment they ticked a file halfway down a long folder.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

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

    // Entering a subfolder reuses this destination rather than pushing a new
    // one, so without this the system back button pops the whole browser off
    // the stack and lands on the home screen - however deep the user had
    // navigated. Selection is unwound first, as elsewhere on Android.
    BackHandler(enabled = true) {
        when {
            state.inSelectionMode -> viewModel.clearSelection()
            viewModel.navigateUp() -> Unit
            else -> onNavigateBack()
        }
    }

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
                        SelectionOverflowMenu(
                            single = state.selected.singleOrNull()
                                ?.let { p -> state.entries.firstOrNull { it.path == p } },
                            onRename = { renameTarget = it },
                            onDetails = viewModel::showDetails,
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
                    onShare = { onShare(state.selected.toList()) },
                    // With one file selected, Details is the more useful fifth
                    // action; with several, zipping them is.
                    onDetails = state.selected.singleOrNull()?.let { path ->
                        { state.entries.firstOrNull { it.path == path }
                            ?.let(viewModel::showDetails) }
                    },
                    onCompress = if (state.selected.size > 1) {
                        { viewModel.compressSelected() }
                    } else {
                        null
                    },
                )
            },
        ) { padding ->
            FileList(state, viewModel, onOpenFile, padding, contentPadding, listState, gridState)
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
                    viewMode = state.viewMode,
                    sort = state.sort,
                    onToggleHidden = viewModel::toggleHidden,
                    onSetViewMode = viewModel::setViewMode,
                    onSetSort = viewModel::setSort,
                    onSelectItems = viewModel::enterSelectionMode,
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Breadcrumbs(crumbs = state.breadcrumbs, onNavigate = viewModel::load)
                FileList(
                    state, viewModel, onOpenFile, PaddingValues(0.dp), contentPadding,
                    listState, gridState,
                )
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

    state.extractTarget?.let { target ->
        ExtractDialog(
            archiveName = target.name,
            destinationName = target.name.substringBeforeLast('.', target.name),
            needsPassword = state.extractNeedsPassword,
            wrongPassword = state.extractWrongPassword,
            onExtract = { password -> viewModel.extract(target.path, password) },
            onDismiss = viewModel::dismissExtract,
        )
    }

    state.detailsTarget?.let { target ->
        DetailsDialog(
            entry = target,
            details = state.details,
            onDismiss = viewModel::dismissDetails,
            onShare = {
                onShare(listOf(target.path))
                viewModel.dismissDetails()
            },
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
    listState: LazyListState,
    gridState: LazyGridState,
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

        else -> {
            val onEntryClick: (FileEntry) -> Unit = { entry ->
                when {
                    state.inSelectionMode -> viewModel.toggleSelection(entry.path)
                    entry.isDir -> viewModel.load(entry.path)
                    // Ask before unpacking: it is a lot of writing to do on a
                    // single tap, and undoing it by hand is worse.
                    entry.category == FileCategory.ARCHIVE -> viewModel.confirmExtract(entry)
                    else -> onOpenFile(entry)
                }
            }
            val listModifier = Modifier.padding(scaffoldPadding).fillMaxSize()

            when (state.viewMode) {
                ViewMode.GRID -> LazyVerticalGrid(
                    // Adaptive rather than a fixed count, so a tablet or a
                    // landscape phone gets more columns instead of enormous
                    // tiles.
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    state = gridState,
                    modifier = listModifier,
                    contentPadding = contentPadding,
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileGridCell(
                            entry = entry,
                            isSelected = entry.path in state.selected,
                            selectionMode = state.inSelectionMode,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { viewModel.toggleSelection(entry.path) },
                        )
                    }
                }

                ViewMode.DETAILED -> LazyColumn(
                    state = listState,
                    modifier = listModifier,
                    contentPadding = contentPadding,
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileDetailRow(
                            entry = entry,
                            isSelected = entry.path in state.selected,
                            selectionMode = state.inSelectionMode,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { viewModel.toggleSelection(entry.path) },
                        )
                    }
                }

                ViewMode.LIST -> LazyColumn(
                    state = listState,
                    modifier = listModifier,
                    contentPadding = contentPadding,
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            isSelected = entry.path in state.selected,
                            selectionMode = state.inSelectionMode,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { viewModel.toggleSelection(entry.path) },
                        )
                    }
                }
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
    viewMode: ViewMode,
    sort: SortOptions,
    onToggleHidden: () -> Unit,
    onSetViewMode: (ViewMode) -> Unit,
    onSetSort: (SortOptions) -> Unit,
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
        HorizontalDivider()

        // Sort was only ever the unlabelled icon in the bar, which is easy to
        // miss; the same options are listed here by name.
        MenuSectionLabel("Sort by")
        listOf(
            SortKey.NAME to "Name",
            SortKey.MODIFIED to "Date",
            SortKey.TYPE to "Type",
            SortKey.SIZE to "Size",
        ).forEach { (key, label) ->
            DropdownMenuItem(
                text = {
                    val arrow = if (sort.key == key) {
                        if (sort.descending) "  ↓" else "  ↑"
                    } else {
                        ""
                    }
                    Text(label + arrow)
                },
                trailingIcon = {
                    if (sort.key == key) Icon(Icons.Default.Check, null)
                },
                onClick = {
                    // Choosing the active key flips direction, which is what a
                    // sort menu is expected to do.
                    val descending = if (sort.key == key) !sort.descending else false
                    onSetSort(sort.copy(key = key, descending = descending))
                    expanded = false
                },
            )
        }

        HorizontalDivider()

        MenuSectionLabel("View as")
        listOf(
            ViewMode.LIST to "List",
            ViewMode.DETAILED to "Detailed list",
            ViewMode.GRID to "Grid",
        ).forEach { (mode, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                trailingIcon = { if (viewMode == mode) Icon(Icons.Default.Check, null) },
                onClick = { onSetViewMode(mode); expanded = false },
            )
        }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
            onClick = { onToggleHidden(); expanded = false },
        )
    }
}

/** Quiet heading inside a dropdown, grouping the options under it. */
@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** Actions that only make sense for exactly one selected file. */
@Composable
private fun SelectionOverflowMenu(
    single: FileEntry?,
    onRename: (FileEntry) -> Unit,
    onDetails: (FileEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }, enabled = single != null) {
        Icon(Icons.Default.MoreVert, "More actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
            onClick = { single?.let(onRename); expanded = false },
        )
        DropdownMenuItem(
            text = { Text("Details") },
            leadingIcon = { Icon(Icons.Default.Info, null) },
            onClick = { single?.let(onDetails); expanded = false },
        )
    }
}
