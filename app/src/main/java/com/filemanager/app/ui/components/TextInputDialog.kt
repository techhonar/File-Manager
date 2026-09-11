package com.filemanager.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Shared dialog for "New folder" and "Rename".
 *
 * Uses [TextFieldValue] rather than a plain String so a rename can pre-select
 * the name and leave the extension alone.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial)) }
    val isValid = value.text.isNotBlank() && value.text.none { it in INVALID_CHARS }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                isError = value.text.isNotEmpty() && !isValid,
                supportingText = {
                    if (value.text.isNotEmpty() && !isValid) {
                        Text("A name cannot contain: $INVALID_CHARS")
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text.trim()) }, enabled = isValid) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Characters Android's filesystems reject in a file name. */
private const val INVALID_CHARS = "/\\:*?\"<>|"
