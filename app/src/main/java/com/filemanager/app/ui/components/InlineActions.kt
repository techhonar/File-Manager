package com.filemanager.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(
                    start = OneUi.ScreenPadding + 60.dp,
                    end = OneUi.ScreenPadding,
                    top = 4.dp,
                    bottom = 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InlineAction(Icons.AutoMirrored.Filled.OpenInNew, "Open", onOpen)
            InlineAction(Icons.Default.ContentCopy, "Copy path", onCopyPath)
            InlineAction(Icons.Default.FolderOpen, "Show in folder", onShowInFolder)
            InlineAction(Icons.Default.Apps, "Open with", onOpenWith)
        }
    }
}

@Composable
private fun InlineAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
