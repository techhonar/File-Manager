package com.filemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.remote.RemoteType
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.TestState

/**
 * Add or edit one network location.
 *
 * Fields appear according to the protocol: only SMB names a share, and only
 * FTP and WebDAV have anything to say about encryption, since SFTP is always
 * encrypted and SMB negotiates it. Showing all of them for every type would
 * leave most of the form meaningless most of the time.
 */
@Composable
fun RemoteServerDialog(
    server: RemoteServer,
    testState: TestState,
    testMessage: String?,
    onChange: (RemoteServer) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var typeMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OneUi.CardShape,
        title = { Text(if (onDelete == null) "Add storage" else "Edit storage") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                // Protocol first: it decides which of the fields below matter.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { typeMenuOpen = true }) {
                        Text(server.type.label)
                    }
                    DropdownMenu(
                        expanded = typeMenuOpen,
                        onDismissRequest = { typeMenuOpen = false },
                    ) {
                        RemoteType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.label) },
                                onClick = {
                                    typeMenuOpen = false
                                    // Carry the port across only when it was
                                    // still the old protocol's default, so a
                                    // deliberately chosen port survives but a
                                    // stale 21 does not follow you to SFTP.
                                    val port = if (server.port == server.type.defaultPort) {
                                        type.defaultPort
                                    } else {
                                        server.port
                                    }
                                    onChange(server.copy(type = type, port = port))
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Protocol",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = server.name,
                    onValueChange = { onChange(server.copy(name = it)) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                Row {
                    OutlinedTextField(
                        value = server.host,
                        onValueChange = { onChange(server.copy(host = it.trim())) },
                        label = { Text("Host") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(2f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = server.port.toString(),
                        onValueChange = { text ->
                            // Empty while being retyped, so fall back to the
                            // protocol default rather than refusing the edit.
                            val port = text.filter { it.isDigit() }.take(5).toIntOrNull()
                            onChange(server.copy(port = port ?: server.type.defaultPort))
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (server.type.hasShare) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server.share,
                        onValueChange = { onChange(server.copy(share = it.trim('/', '\\', ' '))) },
                        label = { Text("Share") },
                        supportingText = { Text("The folder name shared by the server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = server.anonymous,
                        onCheckedChange = { onChange(server.copy(anonymous = it)) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Anonymous", style = MaterialTheme.typography.bodyMedium)
                }

                if (!server.anonymous) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server.username,
                        onValueChange = { onChange(server.copy(username = it.trim())) },
                        label = { Text("User name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = server.password,
                        onValueChange = { onChange(server.copy(password = it)) },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = server.basePath,
                    onValueChange = { onChange(server.copy(basePath = it.ifBlank { "/" })) },
                    label = { Text("Folder to open") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (server.type.hasSecureOption) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = server.secure,
                            onCheckedChange = { onChange(server.copy(secure = it)) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (server.type == RemoteType.WEBDAV) {
                                "Use HTTPS"
                            } else {
                                "Use FTPS"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onTest,
                        enabled = testState != TestState.RUNNING,
                    ) {
                        Text(if (testState == TestState.RUNNING) "Testing…" else "Test")
                    }
                    Spacer(Modifier.width(8.dp))
                    testMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testState == TestState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }

                // Said here rather than buried in a settings page, because it
                // is the kind of thing worth knowing before typing a password
                // for a server that holds anything.
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The password is kept in this app's private storage, " +
                        "not encrypted. SFTP does not check the server's host key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Save") } },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
