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
import androidx.compose.material.icons.filled.Star
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
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
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.FavoritesViewModel
import uniffi.filemanager_core.FileEntry

@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onShowInFolder: (String) -> Unit,
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

    BackHandler(enabled = state.inSelectionMode) { viewModel.clearSelection() }

    OneUiScreen(
        title = if (state.inSelectionMode) "${state.selected.size} selected" else "Favourites",
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            IconButton(
                onClick = {
                    if (state.inSelectionMode) viewModel.clearSelection() else onNavigateBack()
                },
            ) {
                Icon(
                    if (state.inSelectionMode) Icons.Default.Close
                    else Icons.AutoMirrored.Filled.ArrowBack,
                    if (state.inSelectionMode) "Cancel selection" else "Back",
                )
            }
        },
        actions = {
            if (state.inSelectionMode) {
                SelectAllToggle(
                    allSelected = state.allSelected,
                    onToggle = viewModel::toggleSelectAll,
                )
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
            AnimatedBottomBar(visible = state.inSelectionMode) {
                // Only the mark is removed here; the files themselves are left
                // alone, so this bar carries one action and not the usual set.
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
                            // Locating a favourite is the other thing this
                            // list is for, and it only means anything for one.
                            val single = state.selected.singleOrNull()
                            TextButton(
                                onClick = { single?.let(onShowInFolder) },
                                enabled = single != null,
                            ) {
                                Icon(Icons.Default.FolderOpen, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Show in folder")
                            }
                            TextButton(onClick = viewModel::removeSelected) {
                                Icon(Icons.Default.StarOutline, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Remove")
                            }
                        }
                    }
                }
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
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        Box(Modifier.animateItem()) {
                            SearchResultRow(
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
                }
            }
        }
    }
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
