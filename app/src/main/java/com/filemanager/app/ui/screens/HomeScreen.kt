package com.filemanager.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.icon
import com.filemanager.app.ui.components.label
import com.filemanager.app.viewmodel.CategoryTile
import com.filemanager.app.viewmodel.HomeState
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize

/**
 * The landing page, modelled on Samsung My Files: a storage summary, the
 * category tiles, the volume list, then recent files.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onCategoryClick: (FileCategory) -> Unit,
    onVolumeClick: (StorageVolume) -> Unit,
    onTrashClick: () -> Unit,
    onFileClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            StorageHeader(
                usedBytes = state.usedBytes,
                totalBytes = state.totalBytes,
                isLoading = state.isLoading,
            )
        }

        item {
            SectionTitle("Categories")
            // Two rows of three, built by hand rather than with a nested
            // LazyVerticalGrid -- nesting a lazy grid inside a lazy column
            // needs a fixed height and gains nothing for six fixed tiles.
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tiles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { tile ->
                            CategoryTileCard(
                                tile = tile,
                                onClick = { onCategoryClick(tile.category) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad a short final row so tiles keep their width.
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item {
            SectionTitle("Storage")
            Column(Modifier.padding(horizontal = 12.dp)) {
                state.volumes.forEach { volume ->
                    VolumeRow(volume = volume, onClick = { onVolumeClick(volume) })
                }
                TrashRow(bytes = state.trashBytes, onClick = onTrashClick)
            }
        }

        if (state.recent.isNotEmpty()) {
            item { SectionTitle("Recent files") }
            items(state.recent, key = { it.path }) { entry ->
                SearchResultRow(entry = entry, onClick = { onFileClick(entry) })
            }
        }
    }
}

@Composable
private fun StorageHeader(usedBytes: ULong, totalBytes: ULong, isLoading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Internal storage", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(12.dp))

            val fraction = if (totalBytes > 0uL) {
                (usedBytes.toDouble() / totalBytes.toDouble()).toFloat()
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${formatSize(usedBytes)} used of ${formatSize(totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CategoryTileCard(tile: CategoryTile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tile.category.color().copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tile.category.icon(),
                    contentDescription = null,
                    tint = tile.category.color(),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = tile.category.label(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatSize(tile.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VolumeRow(volume: StorageVolume, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (volume.isRemovable) Icons.Default.SdStorage else Icons.Default.Smartphone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = volume.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun TrashRow(bytes: ULong, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = "Trash",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        Text(
            text = formatSize(bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}
