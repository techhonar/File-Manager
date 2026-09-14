package com.filemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Actions for the current selection, along the bottom edge.
 *
 * Five at most. Rename and Properties apply to exactly one file, so they live
 * in the selection bar's overflow instead - a row of seven icons is a row
 * nobody reads.
 *
 * Bottom rather than top because that is where One UI puts them, and on a
 * tall phone it is the half the thumb can reach. Shared by the browser and
 * the search results so a selection behaves the same wherever it is made.
 *
 * A null callback hides its action, which is how the search results leave out
 * the ones that make no sense there.
 */
@Composable
fun SelectionActionBar(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    onCompress: (() -> Unit)? = null,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                onCopy?.let { ActionItem(Icons.Default.ContentCopy, "Copy", it) }
                onMove?.let { ActionItem(Icons.Default.ContentCut, "Move", it) }
                onShare?.let { ActionItem(Icons.Default.Share, "Share", it) }
                onDetails?.let { ActionItem(Icons.Default.Info, "Details", it) }
                onCompress?.let { ActionItem(Icons.Default.FolderZip, "Zip", it) }
                ActionItem(Icons.Default.Delete, "Delete", onDelete)
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Column(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}
