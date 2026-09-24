package com.filemanager.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.filemanager.app.data.StoragePermission
import com.filemanager.app.ui.FileManagerRoot
import com.filemanager.app.ui.theme.FileManagerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                FileManagerRoot(
                    hasStorageAccess = hasStorageAccess,
                    onRequestAccess = {
                        startActivity(StoragePermission.settingsIntent(this@MainActivity))
                    },
                )
            }
        }
    }
}
