package com.filemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.OneUi

/**
 * Actions for the current selection, on a bar floating over the bottom of
 * the screen as One UI's does: Move, Copy, Share, Delete, and More for the
 * rest - see selectionMoreActions.
 *
 * Bottom rather than top because that is where One UI puts them, and on a
 * tall phone it is the half the thumb can reach. Shared by every list that
 * selects, so a selection behaves the same wherever it is made. A null
 * callback leaves its action out, for a list where it makes no sense.
 */
@Composable
fun SelectionActionBar(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onMove: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    more: List<MoreAction> = emptyList(),
) {
    // White on the light page, with a shadow under it; lifted a step above the
    // cards in dark mode, as One UI's is, where no shadow shows against black.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val barColor = if (dark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = OneUi.GroupShape,
        color = barColor,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            onMove?.let { BarAction(Icons.AutoMirrored.Outlined.DriveFileMove, "Move", it) }
            onCopy?.let { BarAction(Icons.Outlined.ContentCopy, "Copy", it) }
            onShare?.let { BarAction(Icons.Outlined.Share, "Share", it) }
            BarAction(Icons.Outlined.Delete, "Delete", onDelete)
            if (more.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    BarActionContent(Icons.Default.MoreVert, "More", { open = true }, Modifier.fillMaxWidth())
                    DropdownMenu(
                        expanded = open,
                        onDismissRequest = { open = false },
                        // Clear of the bar, as One UI's sits, rather than over
                        // its top edge.
                        offset = DpOffset(0.dp, (-14).dp),
                        shape = OneUi.CardShape,
                        containerColor = barColor,
                    ) {
                        more.forEachIndexed { index, action ->
                            if (index > 0 && action.group != more[index - 1].group) {
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            }
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                onClick = {
                                    open = false
                                    action.onClick()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** An equal share of the bar's width, so the five line up however wide the phone is. */
@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, onClick: () -> Unit) =
    BarActionContent(icon, label, onClick, Modifier.weight(1f))

@Composable
private fun BarActionContent(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(OneUi.ThumbShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
