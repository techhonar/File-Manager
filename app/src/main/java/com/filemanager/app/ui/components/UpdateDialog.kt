package com.filemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.filemanager.app.data.update.AppUpdater
import com.filemanager.app.ui.theme.OneUi
import uniffi.filemanager_core.formatSize

/**
 * What Update App is doing. Nothing is shown while idle, or once the download
 * is ready - the installer takes over from there.
 */
@Composable
fun UpdateDialog(
    state: AppUpdater.State,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        AppUpdater.State.Idle, is AppUpdater.State.Ready -> Unit

        AppUpdater.State.Checking -> UpdateAlert(
            title = "Update App",
            onDismiss = {},
            confirm = { TextButton(onClick = onCancel) { Text("Cancel") } },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(16.dp))
                Text("Looking for a new version…")
            }
        }

        is AppUpdater.State.Downloading -> UpdateAlert(
            title = "Downloading ${state.version}",
            onDismiss = {},
            confirm = { TextButton(onClick = onCancel) { Text("Cancel") } },
        ) {
            Column {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (state.total > 0) {
                        "${formatSize(state.done.toULong())} of ${formatSize(state.total.toULong())}"
                    } else {
                        formatSize(state.done.toULong())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Android will ask you to confirm the update once it has downloaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is AppUpdater.State.UpToDate -> UpdateAlert(
            title = "Up to date",
            onDismiss = onDismiss,
            confirm = { TextButton(onClick = onDismiss) { Text("OK") } },
        ) {
            Text("You have the latest version, ${state.version}.")
        }

        is AppUpdater.State.Failed -> UpdateAlert(
            title = "Could not update",
            onDismiss = onDismiss,
            confirm = { TextButton(onClick = onRetry) { Text("Try again") } },
            dismiss = { TextButton(onClick = onDismiss) { Text("Close") } },
        ) {
            Text(state.message)
        }
    }
}

@Composable
private fun UpdateAlert(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text(title) },
        text = body,
        confirmButton = confirm,
        dismissButton = dismiss,
        // A download is not dismissed by a stray tap outside it; Cancel says
        // what it does.
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
