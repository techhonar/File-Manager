package com.filemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.FolderPickerState

/**
 * Picks a folder by walking to it.
 *
 * A plain text field would be less code and worse: these paths are long, the
 * user cannot see whether one exists until something fails, and the usual
 * guesses - /sdcard, a capital D in Download - are all wrong.
 *
 * Built on Dialog rather than AlertDialog because the list needs a bounded
 * height it can scroll inside, and AlertDialog's text slot fights that.
 */
@Composable
fun FolderPickerDialog(
    state: FolderPickerState,
    volumes: List<StorageVolume>,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = OneUi.CardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onUp, enabled = state.canGoUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up one folder")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Share this folder",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Only worth showing when there is somewhere else to go. On a
                // phone with no card this is one chip that does nothing.
                if (volumes.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        volumes.forEach { volume ->
                            AssistChip(
                                onClick = { onOpen(volume.path) },
                                label = { Text(volume.name) },
                                leadingIcon = {
                                    Icon(
                                        if (volume.isRemovable) Icons.Default.SdCard
                                        else Icons.Default.Smartphone,
                                        null,
                                        Modifier.size(18.dp),
                                    )
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Bounded so a folder of hundreds does not push the
                        // buttons off the bottom of the screen.
                        .heightIn(min = 160.dp, max = 320.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.isLoading -> CircularProgressIndicator()

                        state.error != null -> Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                        )

                        state.folders.isEmpty() -> Text(
                            text = "No folders in here.\nIt can still be shared.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )

                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            items(state.folders, key = { it.path }) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(folder.path) }
                                        .padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    // Enabled even for an empty folder: somewhere to drop
                    // uploads is a perfectly good reason to share one.
                    TextButton(
                        onClick = onConfirm,
                        enabled = !state.isLoading && state.error == null,
                    ) {
                        Text("Use this folder")
                    }
                }
            }
        }
    }
}
