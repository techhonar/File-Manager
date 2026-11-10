package com.filemanager.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.filemanager.app.data.PermanentDelete
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.permanentDelete
import com.filemanager.app.ui.theme.OneUi

/** The volumes mounted now, for knowing what a delete can't undo. Provided at the app's root. */
val LocalVolumes = compositionLocalOf<List<StorageVolume>> { emptyList() }

/**
 * Delete for a selection of [selected] paths: asked about first when it can't
 * be undone - when any of them is on a USB drive, which keeps no trash, as
 * on Samsung's phones. Anything else goes to the trash at once, as it always
 * has, to be restored from there.
 */
@Composable
fun rememberDeleteAction(selected: Collection<String>, onDelete: () -> Unit): () -> Unit {
    val volumes = LocalVolumes.current
    var asking by remember { mutableStateOf<PermanentDelete?>(null) }
    val paths by rememberUpdatedState(selected)
    val delete by rememberUpdatedState(onDelete)

    asking?.let { ask ->
        PermanentDeleteDialog(
            ask = ask,
            onConfirm = {
                asking = null
                delete()
            },
            onDismiss = { asking = null },
        )
    }
    return remember(volumes) {
        {
            val ask = volumes.permanentDelete(paths)
            if (ask == null) delete() else asking = ask
        }
    }
}

@Composable
private fun PermanentDeleteDialog(ask: PermanentDelete, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val all = ask.onDrive == ask.total
    val title = when {
        !all -> "Delete ${countOf(ask.total.toLong(), "item")}?"
        ask.onlyName != null -> "Delete ${ask.onlyName} permanently?"
        else -> "Delete ${countOf(ask.total.toLong(), "item")} permanently?"
    }
    val text = when {
        !all -> "${ask.onDrive} of them ${if (ask.onDrive == 1) "is" else "are"} on ${ask.drive}, which has no trash, " +
            "so ${if (ask.onDrive == 1) "it" else "they"} can't be restored. The rest go to the trash."
        ask.total == 1 -> "It's on ${ask.drive}, which has no trash, so it can't be restored."
        else -> "They're on ${ask.drive}, which has no trash, so they can't be restored."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
