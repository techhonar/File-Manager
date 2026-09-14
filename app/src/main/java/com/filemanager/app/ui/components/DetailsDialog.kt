package com.filemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filemanager.app.viewmodel.FileDetails
import com.filemanager.app.ui.theme.OneUi
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything known about one file or folder.
 *
 * [details] arrives after the sheet opens. A folder's size and contents need a
 * subtree walk, and the owning app is a MediaStore query, so neither is
 * available at the moment of tapping - the rows say so rather than showing a
 * misleading zero.
 */
@Composable
fun DetailsDialog(
    entry: FileEntry,
    details: FileDetails?,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text("Details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("Name", entry.name)
                DetailRow("Type", if (entry.isDir) "Folder" else entry.category.label())

                DetailRow(
                    label = "Size",
                    value = when {
                        !entry.isDir -> formatSize(entry.size)
                        details?.folderBytes != null -> formatSize(details.folderBytes)
                        else -> "Calculating…"
                    },
                )

                if (entry.isDir) {
                    DetailRow(
                        label = "Contains",
                        value = details?.let {
                            if (it.fileCount == null) "Calculating…"
                            else "${it.fileCount} files, ${it.folderCount} folders"
                        } ?: "Calculating…",
                    )
                }

                DetailRow(
                    label = "Last modified",
                    value = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.getDefault())
                        .format(Date(entry.modifiedMs.toLong())),
                )

                // MediaStore only records an owner for files it has indexed,
                // so anything copied in over USB legitimately has none.
                DetailRow(
                    label = "Source app",
                    value = details?.ownerApp ?: if (details == null) "Checking…" else "Unknown",
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))

                Text(
                    text = "Path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (onShare != null) TextButton(onClick = onShare) { Text("Share") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
