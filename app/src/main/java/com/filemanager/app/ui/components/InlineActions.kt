package com.filemanager.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.OneUi

/**
 * Actions that unfold beneath a tapped search result.
 *
 * A result is usually somewhere the user was not expecting, so the useful
 * questions are "where is this?" and "what opens it?" - neither of which the
 * row alone answers. Inline rather than a sheet, so the row it belongs to
 * stays visible above it.
 */
@Composable
fun InlineResultActions(
    visible: Boolean,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onCopyPath: () -> Unit,
    onShowInFolder: () -> Unit,
    modifier: Modifier = Modifier,
    /** For an archive the app can unpack; null for anything else. */
    onExtract: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        // Shared out evenly across the whole width, each action the same size
        // with its icon centred over its label - the way a bottom bar of
        // actions is laid out. They used to start under the file name and be
        // as wide as their labels: four already did not fit a phone, so the
        // last ones were squeezed and their labels broke unevenly, and five
        // ran off the edge. Equal shares fit however many there are, and the
        // icons line up.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            resultActions(onOpen, onExtract, onCopyPath, onShowInFolder, onOpenWith).forEach {
                InlineAction(it.icon, it.label, it.onClick)
            }
        }
    }
}

/**
 * The same actions as a menu, for a result shown as a tile in a grid.
 *
 * A row unfolding beneath a tile would sit under the whole row of tiles,
 * with nothing to say which one it was for. A menu opens on the tile that
 * was tapped.
 */
@Composable
fun ResultActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenWith: () -> Unit,
    onCopyPath: () -> Unit,
    onShowInFolder: () -> Unit,
    /** For an archive the app can unpack; null for anything else. */
    onExtract: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        resultActions(onOpen, onExtract, onCopyPath, onShowInFolder, onOpenWith).forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = {
                    Icon(action.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

private class ResultAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** What can be done with a result, in the order offered, as a row or a menu. */
private fun resultActions(
    onOpen: () -> Unit,
    onExtract: (() -> Unit)?,
    onCopyPath: () -> Unit,
    onShowInFolder: () -> Unit,
    onOpenWith: () -> Unit,
): List<ResultAction> = listOfNotNull(
    ResultAction(Icons.AutoMirrored.Filled.OpenInNew, "Open", onOpen),
    onExtract?.let { ResultAction(Icons.Default.Unarchive, "Extract", it) },
    ResultAction(Icons.Default.ContentCopy, "Copy path", onCopyPath),
    ResultAction(Icons.Default.FolderOpen, "Show in folder", onShowInFolder),
    ResultAction(Icons.Default.Apps, "Open with", onOpenWith),
)

@Composable
private fun RowScope.InlineAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(OneUi.ThumbShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        // Two lines at most, centred: "Show in folder" wraps on a narrow
        // phone, and a centred second line still sits under its icon.
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
