package com.filemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.SearchResultRow
import uniffi.filemanager_core.FileEntry

/**
 * Everything modified in the last week, newest first.
 *
 * Reads the list the home screen already loaded rather than scanning again -
 * it is the same query, and a second full-device walk to show the same rows
 * would be wasteful.
 */
@Composable
fun RecentScreen(
    entries: List<FileEntry>,
    isLoading: Boolean,
    onOpenFile: (FileEntry) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    OneUiScreen(
        title = "Recent files",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        when {
            entries.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                Alignment.Center,
            ) {
                Text(
                    text = if (isLoading) "Looking for recent files…" else "Nothing modified recently",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                items(entries, key = { it.path }) { entry ->
                    SearchResultRow(entry = entry, onClick = { onOpenFile(entry) })
                }
            }
        }
    }
}
