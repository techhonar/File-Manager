package com.filemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.OneUi
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File or folder details.
 *
 * [folderSize] is null while a folder is still being measured, which is why
 * the row says so rather than showing a misleading zero: a folder has no size
 * of its own and the subtree walk can take a moment on a large one.
 */
@Composable
fun PropertiesDialog(
    entry: FileEntry,
    folderSize: ULong?,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    val timestamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(entry.modifiedMs.toLong()))

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text(entry.name) },
        text = {
            Column {
                DetailRow(
                    label = "Type",
                    value = if (entry.isDir) "Folder" else entry.category.label(),
                )
                DetailRow(
                    label = "Size",
                    value = when {
                        !entry.isDir -> formatSize(entry.size)
                        folderSize != null -> formatSize(folderSize)
                        else -> "Calculating…"
                    },
                )
                DetailRow(label = "Modified", value = timestamp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Location",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.path.substringBeforeLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            // Folders cannot be handed to another app as a stream, so sharing
            // one is simply not offered.
            if (onShare != null && !entry.isDir) {
                TextButton(onClick = onShare) { Text("Share") }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
