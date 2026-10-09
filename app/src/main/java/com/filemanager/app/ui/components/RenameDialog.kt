package com.filemanager.app.ui.components

import androidx.compose.runtime.Composable
import uniffi.filemanager_core.FileEntry

/** Asking for [target]'s new name; nothing while it is null. */
@Composable
fun RenameDialog(
    target: FileEntry?,
    onRename: (path: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    target?.let {
        TextInputDialog(
            title = "Rename",
            label = "New name",
            initial = it.name,
            confirmLabel = "Rename",
            onConfirm = { name -> onRename(it.path, name) },
            onDismiss = onDismiss,
        )
    }
}
