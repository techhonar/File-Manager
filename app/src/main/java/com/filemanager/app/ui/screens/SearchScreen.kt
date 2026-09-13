package com.filemanager.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.label
import com.filemanager.app.ui.theme.OneUi
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

    OneUiScreen(title = "Search", modifier = modifier) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(
                query = state.query,
                onQueryChange = viewModel::onQueryChange,
                onClear = viewModel::clear,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = OneUi.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FILTER_CATEGORIES.forEach { category ->
                    CategoryChip(
                        category = category,
                        selected = category in state.categories,
                        onClick = { viewModel.toggleCategory(category) },
                    )
                }
            }

            if (state.isSearching) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OneUi.ScreenPadding)
                        .clip(OneUi.PillShape),
                )
                Text(
                    text = "Scanned ${state.scanned} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = OneUi.ScreenPadding,
                        vertical = 8.dp,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                // Results render while the walk is still running - the list
                // fills in rather than appearing all at once at the end.
                state.results.isNotEmpty() -> LazyColumn(contentPadding = contentPadding) {
                    item {
                        Text(
                            text = if (state.isSearching) {
                                "${state.results.size} so far…"
                            } else {
                                "${state.results.size} results"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = OneUi.ScreenPadding + 8.dp,
                                bottom = 8.dp,
                            ),
                        )
                    }
                    items(state.results, key = { it.path }) { entry ->
                        SearchResultRow(entry = entry, onClick = { onOpenFile(entry) })
                    }
                }

                // Only shown while nothing has turned up yet.
                state.isSearching -> EmptyMessage("Searching…")

                state.hasSearched -> EmptyMessage("No files match")

                else -> EmptyMessage("Search by name, or pick a category")
            }
        }
    }
}

/** One UI search boxes are full pills with no visible outline. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OneUi.ScreenPadding)
            .clip(OneUi.PillShape),
        placeholder = { Text("Search all files") },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Clear") }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

/**
 * Pill chip carrying its category's accent when selected.
 *
 * Hand-rolled rather than FilterChip so the selected state can be a solid
 * fill of the category colour, which is how One UI renders an active filter.
 */
@Composable
private fun CategoryChip(
    category: FileCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) category.color() else MaterialTheme.colorScheme.surface
    val foreground = if (selected) androidx.compose.ui.graphics.Color.White
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(OneUi.PillShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            text = category.label(),
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
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
