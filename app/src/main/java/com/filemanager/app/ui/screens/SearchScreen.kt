package com.filemanager.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.label
import com.filemanager.app.viewmodel.SearchViewModel
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry

/**
 * Global search. Typing drives a debounced scan in Rust; the category chips
 * work on their own, so tapping "Videos" with an empty box lists every video
 * on the device.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search all files") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }
            },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTER_CATEGORIES.forEach { category ->
                val selected = category in state.categories
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.toggleCategory(category) },
                    label = { Text(category.label()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = category.color().copy(alpha = 0.2f),
                    ),
                )
            }
        }

        if (state.isSearching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            Text(
                text = "Scanned ${state.scanned} files",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            state.results.isNotEmpty() -> LazyColumn(contentPadding = contentPadding) {
                item {
                    Text(
                        text = "${state.results.size} results",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.results, key = { it.path }) { entry ->
                    SearchResultRow(entry = entry, onClick = { onOpenFile(entry) })
                }
            }

            state.hasSearched && !state.isSearching -> EmptyMessage("No files match")

            !state.isSearching -> EmptyMessage("Search by name, or pick a category")
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val FILTER_CATEGORIES = listOf(
    FileCategory.IMAGE,
    FileCategory.VIDEO,
    FileCategory.AUDIO,
    FileCategory.DOCUMENT,
    FileCategory.ARCHIVE,
    FileCategory.APK,
)
