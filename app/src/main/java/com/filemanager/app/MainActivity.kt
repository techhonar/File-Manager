package com.filemanager.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.filemanager.app.data.StoragePermission
import com.filemanager.app.data.external.Incoming
import com.filemanager.app.data.external.mimeTypeOf
import com.filemanager.app.ui.FileManagerRoot
import com.filemanager.app.ui.openWithExternalApp
import com.filemanager.app.ui.screens.ExternalViewerScreen
import com.filemanager.app.ui.theme.FileManagerTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** What the app that started this one asked for; see Incoming. */
    private var incoming by mutableStateOf<Incoming?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A folder is opened once. Recreated - on rotation, say - the screen
        // is already showing it.
        incoming = Incoming.of(intent, this)
            ?.takeUnless { savedInstanceState != null && it is Incoming.Folder }

        setContent {
            // Re-checked on every resume rather than held as a one-time value:
            // the user grants all-files access in Settings and comes back, so
            // the only reliable moment to look is when we regain the
            // foreground.
            var hasStorageAccess by remember { mutableStateOf(StoragePermission.isGranted()) }

            LaunchedEffect(Unit) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    hasStorageAccess = StoragePermission.isGranted()
                }
            }

            // Read from the stored preference, so the choice survives a
            // restart and applies before the first frame is drawn.
            val theme by (application as FileManagerApp).settings.theme
                .collectAsState()

            FileManagerTheme(theme = theme) {
                when (val request = incoming) {
                    // Showing another app's file needs no all-files access,
                    // so it is not asked for.
                    is Incoming.View -> ExternalViewerScreen(
                        uri = request.uri,
                        kind = request.kind,
                        onOpenWith = { path ->
                            if (!openWithExternalApp(this@MainActivity, path, forceChooser = true)) {
                                Toast.makeText(this@MainActivity, "No app can open this file", Toast.LENGTH_LONG).show()
                            }
                        },
                        onClose = ::finish,
                    )
                    else -> FileManagerRoot(
                        hasStorageAccess = hasStorageAccess,
                        onRequestAccess = {
                            startActivity(StoragePermission.settingsIntent(this@MainActivity))
                        },
                        openFolder = (request as? Incoming.Folder)?.path,
                        onFolderOpened = { incoming = null },
                        picking = request as? Incoming.Pick,
                        onPick = ::sendBack,
                        onCancelPick = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Incoming.of(intent, this)?.let { incoming = it }
    }

    /**
     * Gives the file at [path] to the app that asked for one, and closes.
     * Through FileProvider, with permission to read it, since the asking app
     * can't read this one's storage itself.
     */
    private fun sendBack(path: String) {
        val file = File(path)
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrElse {
            Toast.makeText(this, "This file can't be sent", Toast.LENGTH_LONG).show()
            return
        }
        val result = Intent().apply {
            setDataAndType(uri, mimeTypeOf(file.name) ?: "application/octet-stream")
            // Some apps read the pick from here rather than from the data.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
