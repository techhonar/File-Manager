package com.filemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import com.filemanager.app.ui.components.InlineResultActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.filemanager.app.ui.components.FileDetailRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.SelectionActionBar
import com.filemanager.app.ui.components.TextInputDialog
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.filemanager.app.ui.components.FileGridCell
import com.filemanager.app.viewmodel.ViewMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.merge
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.label
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.SearchViewModel
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry

/**
 * Global search. Typing drives a debounced scan in Rust; the category chips
 * work on their own, so tapping "Videos" with an empty box lists every video
 * on the device.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onShare: (List<String>) -> Unit,
    onOpenWith: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onShowInFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * Focus the field and raise the keyboard on arrival.
     *
     * False when the screen was opened from a category tile - the user asked
     * for Images, not to type, and a keyboard covering half the results would
     * be in the way.
     */
    autoFocus: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    // Hoisted so switching layout or entering selection does not send the
    // user back to the top of a long result list.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // Whether the user has taken hold of the list since the last query. A
    // keyed lazy list keeps its place by following whichever item was on top,
    // so re-ordering the finished results drags the view down to wherever that
    // item ended up - opening Videos would land the user in the middle of the
    // list. Putting it back at the top is right unless they had already
    // started scrolling, which is the one case where being moved is worse.
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state.query, state.categories) { userScrolled = false }
    // Drags rather than isScrollInProgress, which is also true while the
    // scroll below is running - that would set this on the first reset and
    // stop every later one.
    LaunchedEffect(listState, gridState) {
        merge(
            listState.interactionSource.interactions,
            gridState.interactionSource.interactions,
        ).collect { if (it is DragInteraction.Start) userScrolled = true }
    }
    LaunchedEffect(state.resultsEpoch) {
        if (userScrolled) return@LaunchedEffect
        // Both, because the layout can be switched while results are coming in
        // and only one of them is on screen at a time.
        runCatching {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
        }
    }
    // Which result has its actions unfolded. One at a time, so the list does
    // not turn into a column of expanded panels.
    var expandedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Back unwinds a selection before leaving, which is what every other
    // Android list does - and what the browser already did.
    BackHandler(enabled = state.inSelectionMode) {
        viewModel.clearSelection()
    }

    OneUiScreen(
        title = if (state.inSelectionMode) "${state.selected.size} selected" else "Search",
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            if (state.inSelectionMode) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Default.Close, "Cancel selection")
                }
            }
        },
        actions = {
            if (state.inSelectionMode) {
                SelectAllToggle(
                    allSelected = state.allSelected,
                    onToggle = viewModel::toggleSelectAll,
                )
                // Rename applies to exactly one file, so it is an action here
                // rather than a sixth icon in the bottom bar.
                val single = state.selected.singleOrNull()
                    ?.let { p -> state.results.firstOrNull { it.path == p } }
                IconButton(onClick = { renameTarget = single }, enabled = single != null) {
                    Icon(Icons.Default.DriveFileRenameOutline, "Rename")
                }
            } else if (state.results.isNotEmpty()) {
                // A category opens this screen, so the layout choice has to be
                // reachable here - it was only ever in the browser.
                ViewModeMenu(current = state.viewMode, onSelect = viewModel::setViewMode)
                IconButton(onClick = viewModel::enterSelectionMode) {
                    Icon(Icons.Default.SelectAll, "Select items")
                }
            }
        },
        bottomBar = {
            if (state.inSelectionMode) {
                SelectionActionBar(
                    onCopy = viewModel::copySelection,
                    onMove = viewModel::cutSelection,
                    onDelete = viewModel::deleteSelection,
                    onShare = { onShare(state.selected.toList()) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onClear = viewModel::clear,
                autoFocus = autoFocus && !state.inSelectionMode,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = OneUi.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FILTER_CATEGORIES.forEach { category ->
                    CategoryChip(
                        category = category,
                        selected = category in state.categories,
                        onClick = { viewModel.toggleCategory(category) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                // Results render while the walk is still running - the list
                // fills in rather than appearing all at once at the end.
                state.results.isNotEmpty() && state.viewMode == ViewMode.GRID ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        state = gridState,
                        contentPadding = contentPadding,
                    ) {
                        items(state.results, key = { it.path }) { entry ->
                            FileGridCell(
                                entry = entry,
                                isSelected = entry.path in state.selected,
                                selectionMode = state.inSelectionMode,
                                onClick = {
                                    if (state.inSelectionMode) viewModel.toggleSelection(entry.path)
                                    else onOpenFile(entry)
                                },
                                onLongClick = { viewModel.toggleSelection(entry.path) },
                            )
                        }
                    }

                state.results.isNotEmpty() && state.viewMode == ViewMode.DETAILED ->
                    LazyColumn(state = listState, contentPadding = contentPadding) {
                        items(state.results, key = { it.path }) { entry ->
                            Column {
                                FileDetailRow(
                                    entry = entry,
                                    isSelected = entry.path in state.selected,
                                    selectionMode = state.inSelectionMode,
                                    onClick = {
                                        when {
                                            state.inSelectionMode ->
                                                viewModel.toggleSelection(entry.path)
                                            expandedPath == entry.path -> expandedPath = null
                                            else -> expandedPath = entry.path
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(entry.path) },
                                )
                                InlineResultActions(
                                    visible = expandedPath == entry.path &&
                                        !state.inSelectionMode,
                                    onOpen = { onOpenFile(entry) },
                                onOpenWith = { onOpenWith(entry.path) },
                                    onCopyPath = { onCopyPath(entry.path) },
                                    onShowInFolder = {
                                        expandedPath = null
                                        onShowInFolder(entry.path)
                                    },
                                )
                            }
                        }
                    }

                state.results.isNotEmpty() -> LazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                ) {
                    item {
                        Text(
                            text = "${state.results.size} results",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = OneUi.ScreenPadding + 8.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                    items(state.results, key = { it.path }) { entry ->
                        Column {
                            SearchResultRow(
                                entry = entry,
                                isSelected = entry.path in state.selected,
                                selectionMode = state.inSelectionMode,
                                onClick = {
                                    when {
                                        state.inSelectionMode ->
                                            viewModel.toggleSelection(entry.path)
                                        // A result is usually somewhere the
                                        // user was not expecting, so offer to
                                        // locate it rather than only open it.
                                        expandedPath == entry.path -> expandedPath = null
                                        else -> expandedPath = entry.path
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(entry.path) },
                            )
                            InlineResultActions(
                                visible = expandedPath == entry.path && !state.inSelectionMode,
                                onOpen = { onOpenFile(entry) },
                                onOpenWith = { onOpenWith(entry.path) },
                                onCopyPath = { onCopyPath(entry.path) },
                                onShowInFolder = {
                                    expandedPath = null
                                    onShowInFolder(entry.path)
                                },
                            )
                        }
                    }
                }

                state.hasSearched -> EmptyMessage("No files match")

                else -> EmptyMessage("Search by name, or pick a category")
            }
        }
    }

    RenameDialogHost(
        target = renameTarget,
        onConfirm = { path, name ->
            viewModel.rename(path, name)
            renameTarget = null
        },
        onDismiss = { renameTarget = null },
    )
}

/** One UI search boxes are full pills with no visible outline. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    autoFocus: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        // One frame of delay: requesting focus before the node has been
        // placed throws, and the screen is still animating in on arrival.
        withFrameNanos {}
        runCatching {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OneUi.ScreenPadding)
            .clip(OneUi.PillShape)
            .focusRequester(focusRequester),
        placeholder = { Text("Search all files") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Clear") }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

/**
 * Pill chip carrying its category's accent when selected.
 *
 * Hand-rolled rather than FilterChip so the selected state can be a solid
 * fill of the category colour, which is how One UI renders an active filter.
 */
@Composable
private fun CategoryChip(
    category: FileCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) category.color() else MaterialTheme.colorScheme.surface
    val foreground = if (selected) androidx.compose.ui.graphics.Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(OneUi.PillShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = category.label(),
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameDialogHost(
    target: FileEntry?,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    target?.let {
        TextInputDialog(
            title = "Rename",
            label = "New name",
            initial = it.name,
            confirmLabel = "Rename",
            onConfirm = { name -> onConfirm(it.path, name) },
            onDismiss = onDismiss,
        )
    }
}

private val FILTER_CATEGORIES = listOf(
    FileCategory.IMAGE,
    FileCategory.VIDEO,
    FileCategory.AUDIO,
    FileCategory.DOCUMENT,
    FileCategory.ARCHIVE,
    FileCategory.APK,
)

/** List or grid, for a screen a category tile lands on. */
@Composable
private fun ViewModeMenu(current: ViewMode, onSelect: (ViewMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            if (current == ViewMode.GRID) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
            "Change view",
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            ViewMode.LIST to "List",
            ViewMode.DETAILED to "Detailed list",
            ViewMode.GRID to "Grid",
        ).forEach { (mode, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                trailingIcon = { if (current == mode) Icon(Icons.Default.Check, null) },
                onClick = { onSelect(mode); expanded = false },
            )
        }
    }
}

