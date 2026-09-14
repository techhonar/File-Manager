package com.filemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.FavoritesViewModel
import uniffi.filemanager_core.FileEntry

@Composable
fun FavoritesScreen(
    viewModel: FavoritesViewModel,
    onOpenFile: (FileEntry) -> Unit,
    onShowInFolder: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()

    OneUiScreen(
        title = "Favourites",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        when {
            state.entries.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                Alignment.Center,
            ) {
                Text(
                    text = if (state.isLoading) "Loading…"
                    else "Nothing here yet.\nSelect a file and choose Add to favourites.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                items(state.entries, key = { it.path }) { entry ->
                    SearchResultRow(
                        entry = entry,
                        // A favourite is opened by tapping and located by
                        // long-press, which is the pair of things a list of
                        // scattered files is actually for.
                        onClick = { onOpenFile(entry) },
                        onLongClick = { onShowInFolder(entry.path) },
                    )
                }
            }
        }
    }
}
