package com.filemanager.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.Check
import com.filemanager.app.data.viewer.ViewerKind
import com.filemanager.app.data.viewer.viewerKind
import com.filemanager.app.ui.components.AnimatedBottomBar
import com.filemanager.app.ui.components.FileDetailRow
import com.filemanager.app.ui.components.FileGridCell
import com.filemanager.app.ui.components.CompressDialog
import com.filemanager.app.viewmodel.ViewMode
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.freeName
import com.filemanager.app.data.isExtractable
import androidx.activity.compose.BackHandler
import com.filemanager.app.ui.components.ExtractDialogHost
import java.io.File
import kotlinx.coroutines.delay
import com.filemanager.app.ui.components.FileRow
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.rememberTitleState
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.components.PullToRefreshFromTop
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.SelectionActionBar
import com.filemanager.app.ui.components.TextInputDialog
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.BrowserViewModel
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.SortKey
import uniffi.filemanager_core.SortOptions
import com.filemanager.app.ui.components.selectionTitle
import com.filemanager.app.ui.components.selectionMoreActions
import com.filemanager.app.ui.components.MoreAction
import com.filemanager.app.ui.components.DetailsDialogHost
import com.filemanager.app.ui.components.RenameDialog
import com.filemanager.app.ui.components.CancelSelection

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
    onOpenWith: (String) -> Unit,
    onNavigateBack: () -> Unit,
    /** Puts files on the clipboard, for pasting into another app. */
    onCopyToClipboard: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Choosing a file for another app, where tapping an archive picks it
    // rather than offering to unpack it.
    picking: Boolean = false,
    /** A new text file was made; open it for typing. */
    onEditNewFile: (String) -> Unit = {},
    /** The phone's storage, for choosing where an archive is extracted. */
    volumes: List<StorageVolume> = emptyList(),
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
    // Read when a pull starts: see the refresh below.
    val titleState = rememberTitleState()

    // Scroll the highlighted file into view once the folder has loaded, then
    // let the marker fade. Keyed on the path and the entries so it runs after
    // the list exists, not before.
    LaunchedEffect(state.highlightPath, state.entries) {
        val target = state.highlightPath ?: return@LaunchedEffect
        val index = state.entries.indexOfFirst { it.path == target }
        if (index < 0) return@LaunchedEffect

        // A little above centre, so the row is clearly in view rather than
        // pinned to the very top edge.
        runCatching { listState.animateScrollToItem(index.coerceAtLeast(0)) }
        delay(HIGHLIGHT_DURATION_MS)
        viewModel.clearHighlight()
    }

    // Held long enough for the gesture to complete, not for the scan: the work
    // is quick and invisible, and snapping the indicator away the instant it
    // started would read as nothing having happened.
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            delay(REFRESH_INDICATOR_MS)
            isRefreshing = false
        }
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewTextDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showCompressDialog by remember { mutableStateOf(false) }

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

    // One screen whose bars change, not two screens swapped between. The
    // earlier version rendered a Scaffold in selection mode and a OneUiScreen
    // otherwise, which meant the list was torn down and rebuilt the moment a
    // file was ticked - taking its scroll position with it and dropping the
    // user back at the top of the folder.
    OneUiScreen(
        title = if (state.inSelectionMode) selectionTitle(state.selected.size) else folderName,
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        titleState = titleState,
        navigationIcon = {
            if (state.inSelectionMode) {
                SelectAllToggle(allSelected = state.allSelected, onToggle = viewModel::toggleSelectAll)
            } else {
                IconButton(onClick = { if (!viewModel.navigateUp()) onNavigateBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                }
            }
        },
        actions = {
            if (state.inSelectionMode) {
                CancelSelection(onCancel = viewModel::clearSelection)
            } else {
                if (clipboard != null) {
                    IconButton(onClick = viewModel::paste) {
                        Icon(Icons.Default.ContentPaste, "Paste")
                    }
                }
                CreateMenu(
                    onNewFolder = { showNewFolderDialog = true },
                    onNewFile = { showNewFileDialog = true },
                    onNewTextFile = { showNewTextDialog = true },
                )
                SortMenu(current = state.sort, onSelect = viewModel::setSort)
                OverflowMenu(
                    showHidden = state.showHidden,
                    viewMode = state.viewMode,
                    sort = state.sort,
                    onToggleHidden = viewModel::toggleShowHidden,
                    onSetViewMode = viewModel::setViewMode,
                    onSetSort = viewModel::setSort,
                    onSelectItems = viewModel::enterSelectionMode,
                )
            }
        },
        bottomBar = {
            // Only with something chosen: "Select items" with nothing ticked
            // has nothing for it to act on.
            AnimatedBottomBar(visible = state.selected.isNotEmpty()) {
                val selected = state.entries.filter { it.path in state.selected }
                SelectionActionBar(
                    onMove = viewModel::cut,
                    onCopy = viewModel::copy,
                    onShare = { onShare(state.selected.toList()) },
                    onDelete = viewModel::deleteSelected,
                    more = selectionMoreActions(
                        selected = selected,
                        allFavorite = state.selected.all { it in state.favorites },
                        onCopyToClipboard = {
                            onCopyToClipboard(state.selected.toList())
                            viewModel.clearSelection()
                        },
                        onDetails = viewModel.details::show,
                        onRename = { renameTarget = it },
                        onFavorite = viewModel::toggleFavorite,
                        onOpenWith = { onOpenWith(it.path) },
                    ) + listOf(
                        // What only a folder's own list can do: they change
                        // how it lists, or write into it.
                        MoreAction("Compress", group = 2) { showCompressDialog = true },
                        MoreAction(
                            if (state.selected.all { it in state.pinned }) "Unpin" else "Pin to top",
                            group = 2,
                            onClick = viewModel::togglePinned,
                        ),
                        // A name starting with a dot is what hidden means, so
                        // the label follows what the selection is now.
                        MoreAction(
                            if (state.selected.all { it.substringAfterLast('/').startsWith(".") }) "Unhide" else "Hide",
                            group = 2,
                            onClick = viewModel::toggleSelectionHidden,
                        ),
                    ),
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Kept in selection mode too. Hiding it took a row of height out
            // from above the list, and everything below slid up to fill the
            // gap - so ticking one file appeared to scroll the folder. The
            // path is also still worth reading while choosing what to act on.
            Breadcrumbs(
                crumbs = state.breadcrumbs,
                onNavigate = viewModel::load,
                // Shown but inert while selecting: leaving the folder would
                // keep the ticks, which now point at files that are no longer
                // on screen.
                enabled = !state.inSelectionMode,
            )

            // The indicator is the only thing shown. The rescan itself is
            // deliberately silent - a spinner over the list would make a
            // refresh look like a reload of something that is already correct.
            PullToRefreshFromTop(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    viewModel.refreshQuietly()
                },
                atTop = {
                    if (state.viewMode == ViewMode.GRID) !gridState.canScrollBackward
                    else !listState.canScrollBackward
                },
                titleState = titleState,
                modifier = Modifier.fillMaxSize(),
            ) {
                FileList(
                    state, viewModel, onOpenFile, PaddingValues(0.dp), contentPadding,
                    listState, gridState, offerExtract = !picking,
                )
            }
        }
    }

    if (showCompressDialog) {
        val first = state.selected.firstOrNull()?.substringAfterLast('/').orEmpty()
        CompressDialog(
            itemCount = state.selected.size,
            // Asked of the view model, which writes it - so the dialog cannot
            // promise one name and write another, and never names a zip that
            // is already there.
            archiveName = viewModel.compressDestination()?.name
                ?: (first.substringBeforeLast('.', first) + ".zip"),
            onCompress = { password ->
                viewModel.compressSelected(password)
                showCompressDialog = false
            },
            onDismiss = { showCompressDialog = false },
        )
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

    if (showNewTextDialog) {
        TextInputDialog(
            title = "New text file",
            label = "File name",
            // A free name, so Create works without thinking of one.
            initial = remember { freeName(File(state.path), "New text file", "txt").name },
            confirmLabel = "Create",
            onConfirm = { name ->
                showNewTextDialog = false
                viewModel.createFile(name) { path ->
                    if (viewerKind(name) == ViewerKind.TEXT) onEditNewFile(path)
                }
            },
            onDismiss = { showNewTextDialog = false },
        )
    }

    ExtractDialogHost(
        viewModel.extractor,
        volumes,
        snackbarState,
        onShowFolder = viewModel::load,
        currentFolder = state.path,
    )

    DetailsDialogHost(viewModel.details, onShare = { onShare(listOf(it)) })

    RenameDialog(
        target = renameTarget,
        onRename = { path, name ->
            viewModel.rename(path, name)
            viewModel.clearSelection()
            renameTarget = null
        },
        onDismiss = { renameTarget = null },
    )
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
    offerExtract: Boolean,
) {
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
            Pane.LOADING -> Box(
                Modifier.padding(scaffoldPadding).fillMaxSize(),
                Alignment.Center,
            ) { CircularProgressIndicator() }

            Pane.ERROR -> Box(
                Modifier.padding(scaffoldPadding).fillMaxSize(),
                Alignment.Center,
            ) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(32.dp),
                )
            }

            Pane.EMPTY -> Box(
                Modifier.padding(scaffoldPadding).fillMaxSize(),
                Alignment.Center,
            ) {
                Text(
                    "This folder is empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Pane.ITEMS -> {
                val onEntryClick: (FileEntry) -> Unit = { entry ->
                    when {
                        state.inSelectionMode -> viewModel.toggleSelection(entry.path)
                        entry.isDir -> viewModel.load(entry.path)
                        // Ask before unpacking: it is a lot of writing to do on a
                        // single tap, and undoing it by hand is worse. Only the
                        // kinds the core can read - anything else, an .iso say,
                        // goes to whichever app on the phone opens it.
                        offerExtract && isExtractable(entry.name) -> viewModel.extractor.open(entry)
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
                            Box(Modifier.animateItem()) {
                                FileGridCell(
                                    entry = entry,
                                    isSelected = entry.path in state.selected,
                                    selectionMode = state.inSelectionMode,
                                    onClick = { onEntryClick(entry) },
                                    onLongClick = { viewModel.toggleSelection(entry.path) },
                                )
                            }
                        }
                    }

                    ViewMode.DETAILED -> LazyColumn(
                        state = listState,
                        modifier = listModifier,
                        contentPadding = contentPadding,
                    ) {
                        items(state.entries, key = { it.path }) { entry ->
                            Box(Modifier.animateItem()) {
                                FileDetailRow(
                                    entry = entry,
                                    isSelected = entry.path in state.selected,
                                    selectionMode = state.inSelectionMode,
                                    onClick = { onEntryClick(entry) },
                                    onLongClick = { viewModel.toggleSelection(entry.path) },
                                )
                            }
                        }
                    }

                    ViewMode.LIST -> LazyColumn(
                        state = listState,
                        modifier = listModifier,
                        contentPadding = contentPadding,
                    ) {
                        items(state.entries, key = { it.path }) { entry ->
                            Box(Modifier.animateItem()) {
                                FileRow(
                                    entry = entry,
                                    isSelected = entry.path in state.selected,
                                    selectionMode = state.inSelectionMode,
                                    onClick = { onEntryClick(entry) },
                                    onLongClick = { viewModel.toggleSelection(entry.path) },
                                    isPinned = entry.path in state.pinned,
                                    isHighlighted = entry.path == state.highlightPath,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Horizontally scrolling path, each segment tappable. */
@Composable
private fun Breadcrumbs(
    crumbs: List<Pair<String, String>>,
    onNavigate: (String) -> Unit,
    enabled: Boolean = true,
) {
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
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    index == crumbs.lastIndex -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(enabled = enabled) { onNavigate(path) }
                    .padding(6.dp),
            )
        }
    }
}

/** The "+" action: a folder or an empty file. */
@Composable
private fun CreateMenu(
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onNewTextFile: () -> Unit,
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
            text = { Text("New text file") },
            leadingIcon = { Icon(Icons.Default.EditNote, null) },
            onClick = { onNewTextFile(); expanded = false },
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

/** Long enough to notice the row, short enough not to look stuck. */
private const val HIGHLIGHT_DURATION_MS = 1_200L

/** How long the pull-to-refresh indicator stays after the gesture. */
private const val REFRESH_INDICATOR_MS = 600L
