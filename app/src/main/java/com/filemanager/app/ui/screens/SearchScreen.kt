package com.filemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import com.filemanager.app.ui.components.AnimatedBottomBar
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.isExtractable
import com.filemanager.app.ui.components.ExtractDialogHost
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.SelectionActionBar
import com.filemanager.app.ui.components.ViewModeMenu
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.filemanager.app.ui.components.FileGridCell
import com.filemanager.app.viewmodel.ViewMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.filemanager.app.viewmodel.Category
import com.filemanager.app.viewmodel.SearchViewModel
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import com.filemanager.app.ui.components.selectionTitle
import com.filemanager.app.ui.components.selectionMoreActions
import com.filemanager.app.ui.components.DetailsDialogHost
import com.filemanager.app.ui.components.RenameDialog
import com.filemanager.app.ui.components.CancelSelection

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
    onShowInFolder: (String) -> Unit,
    /** Open a folder in the browser: where an archive was just extracted. */
    onOpenFolder: (String) -> Unit,
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
    /** The phone's storage, for choosing where an archive is extracted. */
    volumes: List<StorageVolume> = emptyList(),
    /** Puts files on the clipboard, for pasting into another app. */
    onCopyToClipboard: (List<String>) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    // A tap opens, as in a folder - an archive asking about extracting it
    // here, so the results take in what came out of it. Everything else a
    // result can have done to it is in the selection's bar, a long-press away.
    val onResultClick: (FileEntry) -> Unit = { entry ->
        when {
            state.inSelectionMode -> viewModel.toggleSelection(entry.path)
            !entry.isDir && isExtractable(entry.name) -> viewModel.extractor.open(entry)
            else -> onOpenFile(entry)
        }
    }
    val snackbarState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    // Hoisted so switching layout or entering selection does not send the
    // user back to the top of a long result list.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // Which list the reader has taken hold of, by its epoch. Everything below
    // keeps the list showing its first row, and this is the one thing that
    // stops it: once someone has scrolled somewhere on purpose, moving them is
    // worse than anything it would be fixing. Kept by epoch rather than as a
    // flag cleared when the query changed, which happened a frame after the
    // new list arrived - long enough for the old scrolling to hold it. Saved
    // like the scroll position itself, so coming back from "Show in folder"
    // finds the reader where they were rather than back at the top.
    var scrolledEpoch by rememberSaveable { mutableIntStateOf(-1) }
    // Drags rather than isScrollInProgress, which is also true while the list
    // is being moved by anything else.
    LaunchedEffect(listState, gridState) {
        merge(
            listState.interactionSource.interactions,
            gridState.interactionSource.interactions,
        ).collect { if (it is DragInteraction.Start) scrolledEpoch = state.resultsEpoch }
    }
    // Held at the top until the reader takes hold, on every change to the
    // results. It used to wait for their number to change. A lazy list keeps
    // its place by the key of its first row, so as the walk found files newer
    // than those on screen and put them above, the list followed its old first
    // row down - and once the page was full, every update was a thousand rows
    // like the last, nothing put it back, and the category opened partway
    // down. Only on phones holding more than a thousand of the type, which is
    // why it showed on some and not others.
    //
    // Requested in the composition that brings the results rather than
    // scrolled to afterwards: the request is honoured by the same layout pass
    // that places them, where a scroll landed a frame after the list had been
    // drawn in the wrong place. Both states, because the layout can be
    // switched and only one of them is on screen at a time.
    val results = state.results
    val pinned = remember { PinnedResults() }
    SideEffect {
        if (pinned.results === results) return@SideEffect
        pinned.results = results
        val reading = scrolledEpoch == state.resultsEpoch ||
            listState.isScrollInProgress || gridState.isScrollInProgress
        if (!reading) {
            listState.requestScrollToItem(0)
            gridState.requestScrollToItem(0)
        }
    }
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
        // Named after what is on screen. Opening Videos from the home screen
        // landed on a page headed "Search", which is where it happens to be
        // built but not what the user asked for.
        // A safe call rather than a null check and a member access: `state`
        // comes from collectAsState, so it is a delegated property and reading
        // it twice is two calls - which means no smart cast, and the second
        // read is still nullable however the first one turned out.
        title = if (state.inSelectionMode) {
            selectionTitle(state.selected.size)
        } else {
            state.category?.label() ?: "Search"
        },
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            if (state.inSelectionMode) {
                SelectAllToggle(allSelected = state.allSelected, onToggle = viewModel::toggleSelectAll)
            }
        },
        actions = {
            if (state.inSelectionMode) {
                CancelSelection(onCancel = viewModel::clearSelection)
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
            AnimatedBottomBar(visible = state.selected.isNotEmpty()) {
                SelectionActionBar(
                    onMove = viewModel::cutSelection,
                    onCopy = viewModel::copySelection,
                    onShare = { onShare(state.selected.toList()) },
                    onDelete = viewModel::deleteSelection,
                    more = selectionMoreActions(
                        selected = state.results.filter { it.path in state.selected },
                        allFavorite = state.selected.all { it in favorites },
                        onCopyToClipboard = {
                            onCopyToClipboard(state.selected.toList())
                            viewModel.clearSelection()
                        },
                        onDetails = viewModel.details::show,
                        onRename = { renameTarget = it },
                        onFavorite = viewModel::toggleFavorite,
                        onOpenWith = { onOpenWith(it.path) },
                        // A result is usually somewhere the user was not
                        // expecting, so where it is is worth a tap.
                        onShowInFolder = {
                            viewModel.clearSelection()
                            onShowInFolder(it.path)
                        },
                    ),
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
                CATEGORY_CHIPS.forEach { category ->
                    CategoryChip(
                        category = category,
                        selected = category == state.category,
                        onClick = { viewModel.toggleCategory(category) },
                    )
                }
            }

            // A scan that is still running while results are already showing.
            // The spinner below only appears when there is nothing at all, and
            // without this a partial list looks like the whole answer.
            if (state.isSearching && state.results.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))

            Crossfade(
                targetState = when {
                    state.results.isNotEmpty() -> Pane.ITEMS
                    state.isSearching -> Pane.LOADING
                    else -> Pane.EMPTY
                },
                animationSpec = PaneFade,
                label = "results",
            ) { pane ->
                when (pane) {
                    // Results render while the walk is still running - the list
                    // fills in rather than appearing all at once at the end.
                    Pane.ITEMS -> when {
                        state.viewMode == ViewMode.GRID ->
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 108.dp),
                                state = gridState,
                                contentPadding = contentPadding,
                            ) {
                                items(state.results, key = { it.path }) { entry ->
                                    Box(Modifier.animateItem(placementSpec = null)) {
                                        FileGridCell(
                                            entry = entry,
                                            isSelected = entry.path in state.selected,
                                            selectionMode = state.inSelectionMode,
                                            onClick = { onResultClick(entry) },
                                            onLongClick = { viewModel.toggleSelection(entry.path) },
                                        )
                                    }
                                }
                            }

                        state.viewMode == ViewMode.DETAILED ->
                            LazyColumn(state = listState, contentPadding = contentPadding) {
                                items(state.results, key = { it.path }) { entry ->
                                    Box(Modifier.animateItem(placementSpec = null)) {
                                        FileDetailRow(
                                            entry = entry,
                                            isSelected = entry.path in state.selected,
                                            selectionMode = state.inSelectionMode,
                                            onClick = { onResultClick(entry) },
                                            onLongClick = { viewModel.toggleSelection(entry.path) },
                                        )
                                    }
                                }
                            }

                        else -> LazyColumn(
                            state = listState,
                            contentPadding = contentPadding,
                        ) {
                            item {
                                Text(
                                    // The count is what the walk found; the list is
                                    // the page of it that crossed from Rust. Saying
                                    // only the page size would under-report a loose
                                    // query by an order of magnitude.
                                    text = if (state.truncated) {
                                        "${state.total} results, showing ${state.results.size}"
                                    } else {
                                        "${state.total} results"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = OneUi.ScreenPadding + 8.dp,
                                        bottom = 8.dp,
                                    ),
                                )
                            }
                            items(state.results, key = { it.path }) { entry ->
                                Box(Modifier.animateItem(placementSpec = null)) {
                                    SearchResultRow(
                                        entry = entry,
                                        isSelected = entry.path in state.selected,
                                        selectionMode = state.inSelectionMode,
                                        onClick = { onResultClick(entry) },
                                        onLongClick = { viewModel.toggleSelection(entry.path) },
                                    )
                                }
                            }
                        }
                    }

                    // Before "no files match": a walk that has not finished has
                    // not established that. A category opens onto an empty screen
                    // and stays that way until the first page arrives, which on a
                    // full device is a few seconds.
                    Pane.LOADING -> SearchingMessage()

                    else -> EmptyMessage(
                        if (state.hasSearched) "No files match"
                        else "Search by name, or pick a category",
                    )
                }
            }
        }
    }

    ExtractDialogHost(viewModel.extractor, volumes, snackbarState, onShowFolder = onOpenFolder)

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
    category: Category,
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
private fun SearchingMessage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Searching…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** In the home screen's order, where the two share tiles. */
private val CATEGORY_CHIPS = listOf(
    Category.OfType(FileCategory.IMAGE),
    Category.OfType(FileCategory.VIDEO),
    Category.OfType(FileCategory.AUDIO),
    Category.OfType(FileCategory.DOCUMENT),
    Category.Downloads,
    Category.OfType(FileCategory.ARCHIVE),
    Category.OfType(FileCategory.APK),
)

/** The results the list was last held at the top for. See SearchScreen. */
private class PinnedResults {
    var results: List<FileEntry>? = null
}
