package com.filemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.OneUi

/**
 * Asks before unpacking an archive, and for a password when it needs one.
 *
 * Tapping a zip used to extract it immediately, which is a surprising amount
 * of writing to do on a single tap - and impossible to undo if the archive
 * holds hundreds of files.
 *
 * [needsPassword] is known before extraction starts, so an encrypted archive
 * asks up front rather than failing partway and leaving half a folder behind.
 * [wrongPassword] re-opens the dialog after a failed attempt.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ExtractDialog(
    archiveName: String,
    destinationName: String,
    needsPassword: Boolean,
    wrongPassword: Boolean,
    onExtract: (password: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // A dialog that exists to take a password should be ready to take one.
    // Keyed on needsPassword because the field only appears once the archive
    // has been checked, which happens after the dialog is already up.
    LaunchedEffect(needsPassword) {
        if (!needsPassword) return@LaunchedEffect
        withFrameNanos {}
        runCatching {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text(if (needsPassword) "Protected archive" else "Extract archive") },
        text = {
            Column {
                Text(
                    text = "Extract \"$archiveName\" into a folder named " +
                        "\"$destinationName\" here?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (needsPassword) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        isError = wrongPassword,
                        supportingText = {
                            if (wrongPassword) Text("That password did not work")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { if (password.isNotEmpty()) onExtract(password) },
                        ),
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                // Extracting with an empty password would just fail, so the
                // button waits until there is something to try.
                enabled = !needsPassword || password.isNotEmpty(),
                onClick = { onExtract(password.takeIf { needsPassword }) },
            ) {
                Text("Extract")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
