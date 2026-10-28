package com.filemanager.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.transfer.Transfer
import com.filemanager.app.data.transfer.placingTitle
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.PlaceController
import com.filemanager.app.viewmodel.PlaceSheet
import com.filemanager.app.viewmodel.PlaceSpot

/**
 * Copying and moving, for the whole app, over whatever screen is showing:
 * the Select folder sheet, the pop-up that follows the copy it starts, and
 * how that ended, said on [snackbarState] with Show in folder - which opens
 * the folder through [onShowInFolder], with the first item marked. Not
 * offered for [currentFolder], the folder already on screen.
 */
@Composable
fun PlaceHost(
    placer: PlaceController,
    volumes: List<StorageVolume>,
    snackbarState: SnackbarHostState,
    onShowInFolder: (folder: String, highlight: String?) -> Unit,
    currentFolder: String? = null,
) {
    val openSheet by placer.sheet.collectAsState()
    val servers by placer.serverChoices.collectAsState()
    val running by placer.running.collectAsState()
    val popupHidden by placer.popupHidden.collectAsState()
    val outcome by placer.outcome.collectAsState()
    val showInFolder by rememberUpdatedState(onShowInFolder)
    val here by rememberUpdatedState(currentFolder)

    LaunchedEffect(outcome) {
        val ended = outcome ?: return@LaunchedEffect
        val folder = ended.folder?.takeUnless { it == here }
        val result = snackbarState.showSnackbar(
            message = ended.message,
            actionLabel = folder?.let { "Show in folder" },
            // Long rather than the default for one with an action, which
            // stays until it is tapped and holds back everything said after.
            duration = if (folder != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        placer.consumeOutcome()
        if (result == SnackbarResult.ActionPerformed && folder != null) showInFolder(folder, ended.highlight)
    }

    // Kept for the way out, so the sheet slides down with what it showed.
    var shown by remember { mutableStateOf<PlaceSheet?>(null) }
    openSheet?.let { shown = it }
    val open = openSheet != null

    BackHandler(enabled = open) { placer.close() }
    AnimatedVisibility(open, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
        Scrim(onClick = placer::close)
    }
    AnimatedVisibility(
        open,
        enter = slideInVertically(tween(300)) { it },
        exit = slideOutVertically(tween(240)) { it },
    ) {
        shown?.let { current ->
            SelectFolderSheet(
                sheet = current,
                volumes = volumes,
                servers = servers,
                onVolume = placer::openVolume,
                onServer = placer::openServer,
                onEnter = placer::enter,
                onNewFolder = placer::newFolder,
                onCancel = placer::close,
                onConfirm = placer::confirm,
            )
        }
    }

    val transfer = running
    if (transfer != null && !popupHidden) {
        TransferPopup(transfer, onCancel = placer::cancelRunning, onHide = placer::hidePopup)
    }
}

/** Dims what is behind, and closes what is in front when tapped. */
@Composable
private fun Scrim(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}

/**
 * Where to copy or move to, as One UI asks it: the storage and the saved
 * servers as chips, the way there as a breadcrumb, the folder's contents -
 * folders to go into, files only to see - and Copy here or Move here at the
 * bottom with how many are going.
 */
@Composable
fun SelectFolderSheet(
    sheet: PlaceSheet,
    volumes: List<StorageVolume>,
    servers: List<RemoteServer>,
    onVolume: (StorageVolume) -> Unit,
    onServer: (RemoteServer) -> Unit,
    onEnter: (String) -> Unit,
    onNewFolder: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.93f)
                // Taps on the sheet stay on it, not on the dimmed screen behind.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select folder",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { naming = true }, enabled = !sheet.isLoading && sheet.error == null) {
                        Icon(Icons.Outlined.CreateNewFolder, "New folder")
                    }
                }

                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val spot = sheet.spot
                    volumes.forEach { volume ->
                        PlaceChip(
                            label = volume.name,
                            icon = if (volume.isRemovable) Icons.Outlined.SdCard else Icons.Outlined.PhoneAndroid,
                            selected = spot is PlaceSpot.Phone && spot.volume == volume,
                            onClick = { onVolume(volume) },
                        )
                    }
                    servers.forEach { server ->
                        PlaceChip(
                            label = server.label,
                            icon = Icons.Outlined.Dns,
                            selected = spot is PlaceSpot.Server && spot.server.id == server.id,
                            onClick = { onServer(server) },
                        )
                    }
                }

                Breadcrumb(sheet.spot, onEnter, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        sheet.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        sheet.error != null -> Quiet(sheet.error)
                        sheet.entries.isEmpty() -> Quiet("This folder is empty")
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(sheet.entries, key = { it.path }) { entry ->
                                FileRow(
                                    entry = entry,
                                    isSelected = false,
                                    selectionMode = false,
                                    onClick = { if (entry.isDir) onEnter(entry.path) },
                                    onLongClick = {},
                                    // Files are there to show what else is in
                                    // the folder; only folders can be gone into.
                                    modifier = if (entry.isDir) Modifier else Modifier.alpha(0.38f),
                                    preview = false,
                                )
                            }
                        }
                    }
                }

                sheet.note?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GoingTile(sheet)
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onConfirm, enabled = sheet.canPlace, modifier = Modifier.weight(1f)) {
                        Text(if (sheet.move) "Move here" else "Copy here", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (naming) {
        TextInputDialog(
            title = "New folder",
            label = "Folder name",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                naming = false
                onNewFolder(name.trim())
            },
            onDismiss = { naming = false },
        )
    }
}

/** A place to copy to: the phone's storage, a card, or a server. */
@Composable
private fun PlaceChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(OneUi.PillShape)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * The way to the folder on show, from the top of its storage or server, each
 * step tappable to go back to it. The folder on show is in the accent colour.
 */
@Composable
private fun Breadcrumb(spot: PlaceSpot, onEnter: (String) -> Unit, modifier: Modifier = Modifier) {
    val steps = breadcrumbSteps(spot)
    val scroll = rememberScrollState()
    LaunchedEffect(steps.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(OneUi.PillShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, (label, path) ->
            val last = index == steps.lastIndex
            if (index > 0) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !last) { onEnter(path) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Each step of the way to [spot]'s folder, as a label and the path it opens. */
internal fun breadcrumbSteps(spot: PlaceSpot): List<Pair<String, String>> {
    val (rootLabel, root) = when (spot) {
        is PlaceSpot.Phone -> spot.volume.name to spot.volume.path
        is PlaceSpot.Server -> spot.server.label to (spot.server.basePath.trimEnd('/').ifEmpty { "/" })
    }
    val below = spot.path.removePrefix(root).trim('/')
    if (below.isEmpty() || !spot.path.startsWith(root)) return listOf(rootLabel to spot.path)
    var at = root.trimEnd('/')
    return listOf(rootLabel to root) + below.split('/').map { part ->
        at = "$at/$part"
        part to at
    }
}

/** How many are going, on a tile like the first of them, as One UI shows it. */
@Composable
private fun GoingTile(sheet: PlaceSheet) {
    Box(Modifier.size(width = 52.dp, height = 44.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (sheet.sourceFolders?.contains(sheet.sources.first()) == true) {
                    Icons.Outlined.Folder
                } else {
                    Icons.Outlined.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-2).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = sheet.sources.size.let { if (it > 99) "99+" else "$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A line in the middle of the list, where there is nothing to list. */
@Composable
private fun Quiet(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * How far a copy or move has got, at the bottom of the screen as One UI has
 * it: "Copying file…", how many of how many files, the bar and the percent,
 * with Cancel - and Hide pop-up, which leaves it to the notification.
 */
@Composable
fun TransferPopup(transfer: Transfer, onCancel: () -> Unit, onHide: () -> Unit) {
    BackHandler { onHide() }
    val fraction = transfer.fraction ?: 0f
    Box(Modifier.fillMaxSize()) {
        Scrim(onClick = onHide)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = OneUi.GroupShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp)) {
                Text(
                    text = placingTitle(transfer),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
                Row {
                    Text(
                        text = "${transfer.filesDone.coerceAtMost(transfer.fileCount)}/${transfer.fileCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    VerticalDivider(Modifier.height(20.dp))
                    TextButton(onClick = onHide, modifier = Modifier.weight(1f)) { Text("Hide pop-up") }
                }
            }
        }
    }
}
