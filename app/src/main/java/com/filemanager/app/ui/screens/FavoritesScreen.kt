package com.filemanager.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.filemanager.app.ui.components.AnimatedBottomBar
import com.filemanager.app.ui.components.SelectAllToggle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.FavoritesViewModel
import uniffi.filemanager_core.FileEntry
import com.filemanager.app.ui.components.selectionTitle
import com.filemanager.app.ui.components.CancelSelection
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import com.filemanager.app.ui.components.BarItem
import com.filemanager.app.ui.components.CompactActionBar
import com.filemanager.app.ui.components.DetailsDialogHost
import com.filemanager.app.ui.components.DragHandle
import com.filemanager.app.ui.components.FileRow
import com.filemanager.app.ui.components.RenameDialog
import com.filemanager.app.ui.components.RowDrag
import com.filemanager.app.ui.components.selectionMoreActions

/**
 * Favourites, as One UI lays them out: in the order the user keeps them, a
 * tap opening one, and a hold choosing it - then dragged by its handle to a
 * new place, or acted on from a small bar: Unfavourite, Share, Delete, More.
 */
@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onShowInFolder: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onShare: (List<String>) -> Unit = {},
    onOpenWith: (String) -> Unit = {},
    /** Puts files on the clipboard, for pasting into another app. */
    onCopyToClipboard: (List<String>) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    val listState = rememberLazyListState()
    // A row dragged by its handle; while it is, the order on screen is its.
    val drag = remember { RowDrag<FileEntry> { it.path } }
    val entries by rememberUpdatedState(state.entries)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = state.inSelectionMode) { viewModel.clearSelection() }

    OneUiScreen(
        title = if (state.inSelectionMode) selectionTitle(state.selected.size) else "Favourites",
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            if (state.inSelectionMode) {
                SelectAllToggle(allSelected = state.allSelected, onToggle = viewModel::toggleSelectAll)
            } else {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        },
        actions = {
            if (state.inSelectionMode) {
                CancelSelection(onCancel = viewModel::clearSelection)
            } else {
                // Offered even when the list looks empty: every favourite may
                // be hidden, and this is the switch that brings them back.
                FavoritesOverflowMenu(
                    showHidden = state.showHidden,
                    onToggleHidden = viewModel::toggleShowHidden,
                    onSelectItems = if (state.entries.isNotEmpty()) {
                        viewModel::enterSelectionMode
                    } else {
                        null
                    },
                )
            }
        },
        bottomBar = {
            AnimatedBottomBar(visible = state.selected.isNotEmpty()) {
                // "all" once everything is chosen, as One UI words it.
                val all = state.allSelected
                CompactActionBar(
                    items = listOf(
                        // Only the mark goes; the files are left alone.
                        BarItem(Icons.Outlined.StarOutline, "Unfavourite", viewModel::removeSelected),
                        BarItem(Icons.Outlined.Share, "Share") { onShare(state.selected.toList()) },
                        BarItem(Icons.Outlined.Delete, if (all) "Delete all" else "Delete", viewModel::deleteSelection),
                    ),
                    more = selectionMoreActions(
                        selected = state.entries.filter { it.path in state.selected },
                        allFavorite = true,
                        onCopyToClipboard = {
                            onCopyToClipboard(state.selected.toList())
                            viewModel.clearSelection()
                        },
                        onDetails = viewModel.details::show,
                        onRename = { renameTarget = it },
                        // Unfavourite is on the bar.
                        onFavorite = null,
                        onOpenWith = { onOpenWith(it.path) },
                        // Locating a favourite is the other thing this list is for.
                        onShowInFolder = {
                            viewModel.clearSelection()
                            onShowInFolder(it.path)
                        },
                    ),
                )
            }
        },
    ) { padding ->
        Crossfade(
            targetState = if (state.entries.isEmpty()) Pane.EMPTY else Pane.ITEMS,
            animationSpec = PaneFade,
            label = "favorites",
        ) { pane ->
            when (pane) {
                Pane.EMPTY -> Box(
                    Modifier.padding(padding).fillMaxSize(),
                    Alignment.Center,
                ) {
                    Text(
                        text = when {
                            state.isLoading -> "Loading…"
                            // "You have none" and "yours are all hidden" look
                            // identical otherwise, and only one of them is fixed
                            // by adding more favourites.
                            state.hiddenCount > 0 ->
                                "${state.hiddenCount} hidden " +
                                    (if (state.hiddenCount == 1) "favourite is" else "favourites are") +
                                    " not shown.\nTurn on Show hidden files from the menu."
                            else -> "Nothing here yet.\nSelect a file and choose Add to favourites."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    items(drag.order ?: state.entries, key = { it.path }) { entry ->
                        val lifted = drag.dragging == entry.path
                        Box(
                            if (lifted) {
                                // Over its neighbours and under the finger, not
                                // eased into its new place as they are.
                                Modifier
                                    .zIndex(1f)
                                    .graphicsLayer {
                                        translationY = drag.offsetOf(entry.path)
                                        shadowElevation = 8.dp.toPx()
                                    }
                                    .background(MaterialTheme.colorScheme.surface)
                            } else {
                                Modifier.animateItem()
                            },
                        ) {
                            FileRow(
                                entry = entry,
                                isSelected = entry.path in state.selected,
                                selectionMode = state.inSelectionMode,
                                onClick = {
                                    if (state.inSelectionMode) viewModel.toggleSelection(entry.path)
                                    else onOpenFile(entry)
                                },
                                onLongClick = { viewModel.toggleSelection(entry.path) },
                                // The handle, while choosing, as One UI has it.
                                trailing = if (state.inSelectionMode) {
                                    {
                                        DragHandle(
                                            onStart = {
                                                val height = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == entry.path }?.size ?: 0
                                                drag.start(entries, entry.path, height)
                                            },
                                            onDrag = drag::drag,
                                            onEnd = { drag.end()?.let { moved -> viewModel.reorder(moved.map { it.path }) } },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

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

/**
 * The menu behind the three dots.
 *
 * [onSelectItems] is null when there is nothing to select, which leaves the
 * hidden-files switch reachable on an empty list - the case where it matters
 * most, since hiding every favourite is what emptied it.
 */
@Composable
private fun FavoritesOverflowMenu(
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onSelectItems: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, "More options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (onSelectItems != null) {
            DropdownMenuItem(
                text = { Text("Select items") },
                leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                onClick = {
                    onSelectItems()
                    expanded = false
                },
            )
        }
        DropdownMenuItem(
            text = { Text(if (showHidden) "Hide hidden files" else "Show hidden files") },
            leadingIcon = {
                Icon(
                    if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    null,
                )
            },
            onClick = {
                onToggleHidden()
                expanded = false
            },
        )
    }
}
