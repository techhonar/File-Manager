package com.filemanager.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.FileRepository
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.shownPath
import com.filemanager.app.ui.components.AnimatedBottomBar
import com.filemanager.app.ui.components.BarItem
import com.filemanager.app.ui.components.CancelSelection
import com.filemanager.app.ui.components.CompactActionBar
import com.filemanager.app.ui.components.containsText
import com.filemanager.app.ui.components.countOf
import com.filemanager.app.ui.components.modifiedRange
import com.filemanager.app.ui.components.shownTime
import com.filemanager.app.ui.components.FileRow
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.Pane
import com.filemanager.app.ui.components.PaneFade
import com.filemanager.app.ui.components.SelectAllToggle
import com.filemanager.app.ui.components.selectionTitle
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.TrashDetails
import com.filemanager.app.viewmodel.TrashViewModel
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.formatSize

/**
 * The trash, as One UI lays its out: what is in it under how long each item
 * has left, chosen like files anywhere else - held, or with Select - then
 * restored, looked at or deleted for good from a small bar at the bottom.
 */
@Composable
fun TrashScreen(
    viewModel: TrashViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** The phone's storage, to say in words where an item came from. */
    volumes: List<StorageVolume> = emptyList(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // Back unwinds a selection before leaving, as on every other list.
    BackHandler(enabled = state.inSelectionMode) {
        viewModel.clearSelection()
    }

    OneUiScreen(
        title = if (state.inSelectionMode) selectionTitle(state.selected.size) else "Trash",
        subtitle = if (!state.inSelectionMode && state.rows.isNotEmpty()) {
            "${countOf(state.rows.size.toLong(), "item")}  ·  ${formatSize(state.totalBytes)}"
        } else {
            null
        },
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
            } else if (state.rows.isNotEmpty()) {
                TextButton(
                    onClick = viewModel::enterSelectionMode,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    Text("Select")
                }
                TrashMenu(onEmpty = { confirmEmpty = true })
            }
        },
        bottomBar = {
            AnimatedBottomBar(visible = state.selected.isNotEmpty()) {
                // "all" once everything is chosen, as One UI words it.
                val all = state.allSelected
                CompactActionBar(
                    listOf(
                        BarItem(Icons.Outlined.Replay, if (all) "Restore all" else "Restore", viewModel::restoreSelected),
                        BarItem(Icons.Outlined.Info, "Details", viewModel::showDetails),
                        BarItem(Icons.Outlined.Delete, if (all) "Delete all" else "Delete") { confirmDelete = true },
                    ),
                )
            }
        },
    ) { padding ->
        Crossfade(
            targetState = when {
                state.isLoading && state.rows.isEmpty() -> Pane.LOADING
                state.rows.isEmpty() -> Pane.EMPTY
                else -> Pane.ITEMS
            },
            animationSpec = PaneFade,
            label = "trash",
            modifier = Modifier.padding(padding),
        ) { pane ->
            when (pane) {
                Pane.LOADING -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                Pane.EMPTY -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "Trash is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
                    item(key = "note") {
                        Text(
                            text = "Files deleted in My Files are kept here for " +
                                "${FileRepository.TRASH_RETENTION_DAYS} days, then deleted permanently.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = OneUi.ScreenPadding, vertical = 8.dp),
                        )
                    }
                    // Newest first already, so a group's items stay in order
                    // and the groups run from most time left to least.
                    state.rows.groupBy { it.item.daysRemaining }.forEach { (days, rows) ->
                        item(key = "heading $days") {
                            DeletionHeading(deletionHeading(days), Modifier.animateItem())
                        }
                        items(rows, key = { it.item.id }) { row ->
                            Box(Modifier.animateItem()) {
                                FileRow(
                                    entry = row.entry,
                                    isSelected = row.item.id in state.selected,
                                    selectionMode = state.inSelectionMode,
                                    // Nothing in the trash opens until it is
                                    // put back, so a tap chooses it.
                                    onClick = { viewModel.toggleSelection(row.item.id) },
                                    onLongClick = { viewModel.toggleSelection(row.item.id) },
                                    // The copy in the trash has no extension to
                                    // say what decodes it; a photo needs none.
                                    preview = row.entry.category == FileCategory.IMAGE,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.details?.let { details ->
        TrashDetailsDialog(details = details, volumes = volumes, onDismiss = viewModel::dismissDetails)
    }

    if (confirmDelete) {
        val count = state.selected.size.toLong()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            shape = OneUi.CardShape,
            title = { Text("Delete ${countOf(count, "item")} permanently?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(); confirmDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            shape = OneUi.CardShape,
            title = { Text("Empty trash?") },
            text = {
                Text(
                    "${countOf(state.rows.size.toLong(), "item")} will be deleted permanently. " +
                        "This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.emptyTrash(); confirmEmpty = false }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") }
            },
        )
    }
}

/** "30 days until deletion", and a dotted rule out to the edge, as One UI heads the trash's groups. */
@Composable
private fun DeletionHeading(text: String, modifier: Modifier = Modifier) {
    val rule = MaterialTheme.colorScheme.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = OneUi.ScreenPadding, end = OneUi.ScreenPadding, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.weight(1f).height(1.dp)) {
            drawLine(
                color = rule,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
        }
    }
}

/** The trash's own overflow: emptying it all. */
@Composable
private fun TrashMenu(onEmpty: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, "More options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(text = { Text("Empty") }, onClick = { expanded = false; onEmpty() })
    }
}

/**
 * Details of what is chosen in the trash, as One UI gives them: for one item,
 * its size, when it last changed, where it came from and when it goes; for
 * several, how many, their size together, the span of their dates and what
 * they are.
 */
@Composable
private fun TrashDetailsDialog(details: TrashDetails, volumes: List<StorageVolume>, onDismiss: () -> Unit) {
    val items = details.items
    val single = items.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text("Details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = single?.name ?: countOf(items.size.toLong(), "item"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (single != null) {
                    Labeled("Size", formatSize(single.size))
                    if (single.isDir) {
                        Labeled(
                            "Contains",
                            details.contents?.let { (files, folders) -> containsText(files.toLong(), folders.toLong()) }
                                ?: "Counting…",
                        )
                    }
                    Labeled("Last modified", details.modifiedMs?.firstOrNull()?.let { shownTime(it) } ?: "…")
                    Labeled("Original path", shownPath(single.originalPath, volumes))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = deletionNote(single),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Labeled(
                        "Total size (all folders and files)",
                        formatSize(items.fold(0uL) { total, item -> total + item.size }),
                    )
                    Labeled("Last modified", details.modifiedMs?.let { modifiedRange(it) } ?: "…")
                    Labeled(
                        "Contains",
                        containsText(
                            files = items.count { !it.isDir }.toLong(),
                            folders = items.count { it.isDir }.toLong(),
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** A detail as One UI sets it out: the label small above, the value under it. */
@Composable
private fun Labeled(label: String, value: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = value,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
