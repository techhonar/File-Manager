package com.filemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.OneUi

/**
 * Confirms zipping the selection, and takes a password if one is wanted.
 *
 * The switch defaults to off, so the common case is still two taps. Turning it
 * on asks twice: a zip cannot be opened to check the password was typed as
 * intended, and a typo would only be discovered once the originals were gone.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CompressDialog(
    itemCount: Int,
    archiveName: String,
    onCompress: (password: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val mismatch = confirm.isNotEmpty() && confirm != password
    val ready = !protect || (password.isNotEmpty() && confirm == password)

    LaunchedEffect(protect) {
        if (!protect) return@LaunchedEffect
        withFrameNanos {}
        runCatching {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text("Compress") },
        text = {
            // Scrollable because the content grows once the switch is on, and
            // on a short screen with the keyboard up the confirm field would
            // otherwise be clipped out of reach.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Zip $itemCount items into \"$archiveName\"?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = protect, onCheckedChange = { protect = it })
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Protect with a password",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (protect) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        isError = mismatch,
                        supportingText = {
                            if (mismatch) Text("The two do not match")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { if (ready) onCompress(password) },
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    // Said plainly here rather than discovered later on a PC.
                    // AES is the only encryption worth writing, and Windows
                    // cannot open it without a separate tool.
                    Text(
                        text = "Uses AES-256. Windows needs 7-Zip or WinRAR to " +
                            "open it. File names inside stay readable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = { onCompress(password.takeIf { protect }) },
            ) {
                Text("Compress")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
