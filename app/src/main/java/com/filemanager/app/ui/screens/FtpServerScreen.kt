package com.filemanager.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.ui.components.FolderPickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.ftpd.FtpServerConfig
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.FtpServerViewModel

/**
 * Turns the phone into an FTP server other machines can connect to.
 *
 * Read-only is the default. Handing the whole of shared storage to anything on
 * the network is the more surprising of the two, and the common use - getting
 * photos off the phone without a cable - does not need writing.
 */
@Composable
fun FtpServerScreen(
    viewModel: FtpServerViewModel,
    /** Offered as shortcuts in the folder picker. */
    volumes: List<StorageVolume>,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()
    val snackbarState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // The server runs either way - Android keeps the service alive - but with
    // no notification there is nothing in the shade to say it is running or to
    // stop it, which for something serving files is worth asking about.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Granted or not, starting goes ahead. */ }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    OneUiScreen(
        title = "FTP server",
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarState) },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = OneUi.ScreenPadding),
        ) {
            StatusCard(
                isRunning = state.isRunning,
                url = state.url,
                config = state.draft,
                onCopy = {
                    state.url?.let { url ->
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip?.setPrimaryClip(
                            android.content.ClipData.newPlainText("FTP address", url),
                        )
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = state.draft.port.toString(),
                onValueChange = { text ->
                    val port = text.filter { it.isDigit() }.take(5).toIntOrNull()
                    viewModel.updateDraft(
                        state.draft.copy(port = port ?: FtpServerConfig.DEFAULT_PORT),
                    )
                },
                label = { Text("Port") },
                supportingText = {
                    Text("1024 or above. Lower ports need root, which an app does not have.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            SettingSwitch(
                checked = state.draft.anonymous,
                onCheckedChange = { viewModel.updateDraft(state.draft.copy(anonymous = it)) },
                title = "Allow anonymous",
                subtitle = "Anyone on the network can connect without a password",
            )

            if (!state.draft.anonymous) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.draft.username,
                    onValueChange = { viewModel.updateDraft(state.draft.copy(username = it.trim())) },
                    label = { Text("User name") },
                    singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.draft.password,
                    onValueChange = { viewModel.updateDraft(state.draft.copy(password = it)) },
                    label = { Text("Password") },
                    singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingSwitch(
                checked = state.draft.readOnly,
                onCheckedChange = { viewModel.updateDraft(state.draft.copy(readOnly = it)) },
                title = "Read only",
                subtitle = "Clients can download but not upload, delete or rename",
            )

            Spacer(Modifier.height(20.dp))

            // A row rather than a line of text: which folder is shared is the
            // most consequential setting here, and until now it was the one
            // thing that could not be changed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.openFolderPicker() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Folder,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Shared folder", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = state.draft.rootPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "FTP sends the password and the files themselves without " +
                    "encryption. Use it on a network you trust, and turn it off " +
                    "when you are done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            if (state.isRunning) {
                if (state.needsRestart) {
                    Text(
                        text = "Restart to apply the changes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = viewModel::restart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Restart")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop server")
                }
            } else {
                Button(
                    onClick = {
                        if (needsNotificationPermission(context)) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Start server")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    state.picker?.let { picker ->
        FolderPickerDialog(
            state = picker,
            volumes = volumes,
            onOpen = viewModel::pickerOpen,
            onUp = viewModel::pickerUp,
            onConfirm = viewModel::pickerConfirm,
            onDismiss = viewModel::pickerCancel,
        )
    }
}

@Composable
private fun StatusCard(
    isRunning: Boolean,
    url: String?,
    config: FtpServerConfig,
    onCopy: () -> Unit,
) {
    Surface(
        shape = OneUi.CardShape,
        color = if (isRunning) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (isRunning) "Running" else "Stopped",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            when {
                isRunning && url != null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, "Copy address")
                    }
                }

                isRunning -> Text(
                    text = "Running, but this device has no network address. " +
                        "Connect to Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> Text(
                    text = "Start the server, then type the address it shows " +
                        "into a file manager or browser on another device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRunning) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(if (config.anonymous) "Anonymous" else "User ${config.username}")
                        append(" · ")
                        append(if (config.readOnly) "read only" else "read and write")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** True when the notification would be silently dropped without asking. */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
