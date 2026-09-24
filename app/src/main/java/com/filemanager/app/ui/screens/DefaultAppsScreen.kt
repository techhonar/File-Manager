package com.filemanager.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.filemanager.app.data.external.Answerer
import com.filemanager.app.data.external.DefaultApps
import com.filemanager.app.data.external.FileRequest
import com.filemanager.app.ui.components.OneUiGroup
import com.filemanager.app.ui.components.OneUiRow
import com.filemanager.app.ui.components.OneUiRowDivider
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.theme.OneUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Which app opens downloads, folders and documents, and a tap to make it
 * this one.
 *
 * Android keeps no "default file manager". Each of these has its own
 * default, set by "Always" in Android's list of apps and cleared from the
 * chosen app's settings - neither of which a user would think to look for.
 */
@Composable
fun DefaultAppsScreen(
    lifecycle: Lifecycle,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    var answerers by remember { mutableStateOf<Map<FileRequest, Answerer>>(emptyMap()) }

    // Looked at again each time the screen comes back: it sends people to
    // Settings and to Android's list, and what they chose shows on return.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            answerers = withContext(Dispatchers.IO) {
                FileRequest.entries.associateWith { DefaultApps.answerer(context, it) }
            }
        }
    }

    fun start(intent: android.content.Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "This phone didn't open that", Toast.LENGTH_LONG).show() }
    }

    OneUiScreen(
        title = "Default apps",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            Note(
                "Android has no single setting for a default file manager. Each of these has its " +
                    "own, chosen with \"Always\" when Android asks which app to use. Tap one that " +
                    "isn't File Manager to change it.",
            )
            OneUiGroup {
                FileRequest.entries.forEachIndexed { index, request ->
                    if (index > 0) OneUiRowDivider(inset = false)
                    val answerer = answerers[request]
                    OneUiRow(
                        title = request.title,
                        subtitle = when (answerer) {
                            null -> "Checking…"
                            Answerer.ThisApp -> "File Manager"
                            Answerer.Asks -> "Asks each time · tap to choose File Manager"
                            is Answerer.OtherApp -> "${answerer.label} · tap to change"
                        },
                        onClick = when (answerer) {
                            // Android's list, where File Manager and "Always" are.
                            Answerer.Asks -> { { start(DefaultApps.tryIntent(context, request)) } }
                            // The other app holds it; only its settings let go.
                            is Answerer.OtherApp -> {
                                {
                                    Toast.makeText(
                                        context,
                                        "Open \"Open by default\" or \"Set as default\" and clear the defaults",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    start(DefaultApps.appSettingsIntent(answerer.packageName))
                                }
                            }
                            else -> null
                        },
                        trailing = if (answerer == Answerer.ThisApp) {
                            { Icon(Icons.Default.Check, "File Manager", tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    )
                }
            }
            Note(
                "Some buttons always open the phone's own file app - its shortcuts, its " +
                    "notifications, \"show in folder\" in the maker's gallery - because they name " +
                    "that app directly, and no other app can answer them. When an app asks you to " +
                    "choose a file, Android's own picker usually opens, with File Manager in its " +
                    "side menu.",
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = OneUi.ScreenPadding + 8.dp, vertical = 16.dp),
    )
}
