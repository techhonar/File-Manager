package com.filemanager.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.filemanager.app.data.viewer.Editing
import com.filemanager.app.data.viewer.TextFiles
import com.filemanager.app.ui.components.OneUiScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A text file's edit in progress. Kept by a ViewModel, which outlives the
 * screen being rebuilt when the phone turns - unsaved typing would otherwise
 * go with it.
 */
class TextEdit : ViewModel() {
    /** The file as it was opened for editing; null when not editing. */
    var opened by mutableStateOf<Editing.Ready?>(null)
        private set
    var text by mutableStateOf("")
        private set
    var changed by mutableStateOf(false)
        private set

    /** Counts saves, so the viewer behind knows to read the file again. */
    var saves by mutableIntStateOf(0)
        private set

    fun begin(ready: Editing.Ready) {
        opened = ready
        text = ready.text
        changed = false
    }

    fun type(new: String) {
        if (new == text) return
        text = new
        changed = true
    }

    fun saved() {
        changed = false
        saves++
    }

    // The text stays, so the editor still shows it while fading out.
    fun end() {
        opened = null
        changed = false
    }
}

/** Opens [file] in [edit], or says why it can't be. */
suspend fun startEditing(context: Context, file: File, edit: TextEdit) {
    val result = withContext(Dispatchers.IO) {
        if (!file.canWrite()) null else runCatching { TextFiles.openForEditing(file) }.getOrNull()
    }
    val refusal = when (result) {
        is Editing.Ready -> return edit.begin(result)
        Editing.TooLarge -> "This file is too large to edit here. Open it with another app to edit it."
        Editing.NotText -> "This file isn't plain text this app can edit without damaging it."
        null -> "This file can't be changed."
    }
    Toast.makeText(context, refusal, Toast.LENGTH_LONG).show()
}

@Composable
fun TextEditor(file: File, edit: TextEdit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held from the start: while the editor fades out after closing, the
    // edit no longer has it.
    val opened = remember { edit.opened } ?: return
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val close: () -> Unit = {
        if (edit.changed) {
            confirmDiscard = true
        } else {
            edit.end()
        }
    }
    BackHandler(onBack = close)

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { TextFiles.save(file, edit.text, opened.encoding, opened.crlf) }.exceptionOrNull()
            }
            saving = false
            if (error == null) {
                edit.saved()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Couldn't save: ${error.message ?: "the file can't be written"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    OneUiScreen(
        title = file.name,
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = close) { Icon(Icons.Default.Close, "Stop editing") }
        },
        actions = {
            IconButton(onClick = { save() }, enabled = edit.changed && !saving) {
                Icon(Icons.Default.Check, "Save")
            }
        },
    ) { padding ->
        BasicTextField(
            value = edit.text,
            onValueChange = edit::type,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // Scrolls itself, keeping the cursor in view, and shrinks above
            // the keyboard rather than going under it.
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to ${file.name} haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    edit.end()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}
