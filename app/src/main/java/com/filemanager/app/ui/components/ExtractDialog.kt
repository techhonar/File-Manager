package com.filemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.filemanager.app.data.ArchiveRow
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.data.shownPath
import com.filemanager.app.data.archiveSummary
import com.filemanager.app.data.visibleRows
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.ExtractController
import com.filemanager.app.viewmodel.ExtractState
import uniffi.filemanager_core.formatSize
import java.io.File

/**
 * Extracting an archive, for whichever screen [controller] belongs to: the
 * question, the folder picker when the user wants it somewhere else, and how
 * it went, said on [snackbarState] - with "Show in folder", which takes the
 * user to the files through [onShowFolder].
 *
 * [currentFolder] is the folder on screen, if any. Files extracted into it
 * are already in view, so the way there is not offered.
 */
@Composable
fun ExtractDialogHost(
    controller: ExtractController,
    volumes: List<StorageVolume>,
    snackbarState: SnackbarHostState,
    onShowFolder: (String) -> Unit,
    currentFolder: String? = null,
) {
    val state by controller.state.collectAsState()
    val picker by controller.picker.state.collectAsState()
    val outcome by controller.outcome.collectAsState()
    val showFolder by rememberUpdatedState(onShowFolder)
    val here by rememberUpdatedState(currentFolder)

    LaunchedEffect(outcome) {
        val ended = outcome ?: return@LaunchedEffect
        val onScreen = here?.let(::File)
        val folder = ended.folder?.takeUnless { File(it) == onScreen }
        val result = snackbarState.showSnackbar(
            message = ended.message,
            actionLabel = folder?.let { "Show in folder" },
            // Long rather than the default for one with an action, which
            // stays until it is tapped and holds back everything said after.
            duration = if (folder != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        controller.consumeOutcome()
        if (result == SnackbarResult.ActionPerformed && folder != null) showFolder(folder)
    }

    state?.let { current ->
        ExtractDialog(
            state = current,
            location = shownPath(current.location, volumes),
            onToggleFolder = controller::toggleFolder,
            onIntoFolder = controller::setIntoFolder,
            onChangeLocation = controller::chooseLocation,
            onShowContents = controller::showContents,
            onExtract = controller::extract,
            onStop = controller::stop,
            onDismiss = controller::dismiss,
        )
    }
    // Over the question, which stays as it was underneath.
    picker?.let { current ->
        FolderPickerDialog(
            state = current,
            volumes = volumes,
            onOpen = controller.picker::enter,
            onUp = controller.picker::up,
            onConfirm = controller::pickLocation,
            onDismiss = controller.picker::close,
        )
    }
}

/**
 * Asks before unpacking an archive - and shows what is in it, where it will
 * go, and the password when it needs one.
 *
 * Tapping an archive used to extract it into a folder beside it with no say
 * in the matter, and no way to see first what that would write. The contents
 * are listed as folders that open, the folder can be any, and the new folder
 * named after the archive can be done without.
 */
@Composable
fun ExtractDialog(
    state: ExtractState,
    /** Where it is going, as the user would read it. */
    location: String,
    onToggleFolder: (String) -> Unit,
    onIntoFolder: (Boolean) -> Unit,
    onChangeLocation: () -> Unit,
    onShowContents: (password: String) -> Unit,
    onExtract: (password: String?) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val extracting = state.progress != null

    Dialog(onDismissRequest = { if (!extracting) onDismiss() }) {
        Surface(
            shape = OneUi.CardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    text = if (state.needsPassword) "Protected archive" else "Extract archive",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = state.archive.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(20.dp))
                Heading("What is inside")
                Contents(state, onToggleFolder)

                Spacer(Modifier.height(20.dp))
                Heading("Extract to")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = location,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onChangeLocation, enabled = !extracting) { Text("Change") }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !extracting) { onIntoFolder(!state.intoFolder) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.intoFolder,
                        onCheckedChange = onIntoFolder,
                        enabled = !extracting,
                    )
                    Text(
                        text = "In a new folder named \"${state.folderName}\"",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!state.intoFolder) {
                    Text(
                        text = "Files already there are kept. One with the same name is " +
                            "added as \"name (1)\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state.needsPassword) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !extracting,
                        isError = state.wrongPassword,
                        supportingText = {
                            if (state.wrongPassword) Text("That password did not work")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { if (password.isNotEmpty()) onExtract(password) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.namesProtected) {
                        TextButton(
                            onClick = { onShowContents(password) },
                            enabled = password.isNotEmpty() && !extracting,
                        ) {
                            Text("Show what is inside")
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                val progress = state.progress
                if (progress != null) {
                    Text(
                        text = if (progress.total > 0) {
                            "Extracting ${progress.done} of ${progress.total} files…"
                        } else {
                            "Extracting… ${progress.done} files"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onStop) { Text("Stop") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(
                            // Extracting with an empty password would only fail,
                            // so the button waits until there is one to try.
                            enabled = !state.needsPassword || password.isNotEmpty(),
                            onClick = { onExtract(password.takeIf { state.needsPassword }) },
                        ) {
                            Text("Extract")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** The archive's folders and files, folders opening in place. */
@Composable
private fun Contents(state: ExtractState, onToggleFolder: (String) -> Unit) {
    val contents = state.contents
    when {
        contents == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Reading the archive…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.contentsNote != null -> Text(
            text = state.contentsNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        contents.isEmpty() -> Text(
            text = "Nothing in it to extract.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> {
            Text(
                text = archiveSummary(contents) { formatSize(it.toULong()) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // Bounded, so a large archive scrolls inside the dialog rather
            // than pushing the choices below it off the screen.
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(visibleRows(contents, state.open), key = { it.path }) { row ->
                    ContentRow(row, open = row.path in state.open, onClick = { onToggleFolder(row.path) })
                }
            }
        }
    }
}

@Composable
private fun ContentRow(row: ArchiveRow, open: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = row.isDir, onClick = onClick)
            .padding(start = (row.depth * 16).dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                !row.isDir -> Icons.Outlined.InsertDriveFile
                open -> Icons.Outlined.FolderOpen
                else -> Icons.Outlined.Folder
            },
            contentDescription = if (row.isDir) (if (open) "Close folder" else "Open folder") else null,
            tint = if (row.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (row.isDir) {
                if (row.files == 1) "1 file" else "${row.files} files"
            } else if (row.size > 0) {
                formatSize(row.size.toULong())
            } else {
                ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
