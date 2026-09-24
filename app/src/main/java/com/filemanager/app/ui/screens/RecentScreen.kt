package com.filemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.AnimatedBottomBar
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.SelectionActionBar
import com.filemanager.app.viewmodel.RecentViewModel
import uniffi.filemanager_core.FileEntry

/** Everything modified in the last week, newest first, and actionable. */
@Composable
fun RecentScreen(
    viewModel: RecentViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onShare: (List<String>) -> Unit,
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

    // Back unwinds a selection before leaving, which is what every other
    // Android list does - and what the browser already did.
    BackHandler(enabled = state.inSelectionMode) {
        viewModel.clearSelection()
    }

    OneUiScreen(
        title = if (state.inSelectionMode) "${state.selected.size} selected" else "Recent files",
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
            } else if (state.entries.isNotEmpty()) {
                IconButton(onClick = viewModel::enterSelectionMode) {
                    Icon(Icons.Default.SelectAll, "Select items")
                }
            }
        },
        bottomBar = {
            AnimatedBottomBar(visible = state.inSelectionMode) {
                SelectionActionBar(
                    onDelete = viewModel::deleteSelection,
                    onCopy = viewModel::copySelection,
                    onMove = viewModel::cutSelection,
                    onShare = { onShare(state.selected.toList()) },
                )
            }
        },
    ) { padding ->
        Crossfade(
            targetState = if (state.entries.isEmpty()) Pane.EMPTY else Pane.ITEMS,
            animationSpec = PaneFade,
            label = "recent",
        ) { pane ->
            when (pane) {
                Pane.EMPTY -> Box(
                    Modifier.padding(padding).fillMaxSize(),
                    Alignment.Center,
                ) {
                    Text(
                        text = if (state.isLoading) "Looking for recent files…"
                        else "Nothing modified recently",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
