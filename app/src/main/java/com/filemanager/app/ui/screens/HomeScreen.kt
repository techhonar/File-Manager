package com.filemanager.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.ui.components.CategoryCircle
import com.filemanager.app.ui.components.OneUiGroup
import com.filemanager.app.ui.components.OneUiRow
import com.filemanager.app.ui.components.OneUiRowDivider
import com.filemanager.app.ui.components.OneUiSectionHeader
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.icon
import com.filemanager.app.ui.components.label
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.CategoryTile
import com.filemanager.app.viewmodel.HomeState
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize

/**
 * The landing page, laid out the way One UI's My Files is: a storage summary
 * card, a grid of category circles, then rounded groups for volumes and
 * recent files.
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
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { StorageSummaryCard(state) }

        item {
            // Two rows of three circles. Built by hand rather than with a
            // nested LazyVerticalGrid, which would need a fixed height and
            // gains nothing for six fixed tiles.
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                state.tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { tile ->
                            CategoryTileItem(
                                tile = tile,
                                onClick = { onCategoryClick(tile.category) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad a short final row so the tiles keep their width
                        // instead of stretching.
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item { OneUiSectionHeader("Storage") }
        item {
            OneUiGroup {
                state.volumes.forEachIndexed { index, volume ->
                    OneUiRow(
                        title = volume.name,
                        subtitle = volume.path,
                        icon = if (volume.isRemovable) Icons.Default.SdStorage
                        else Icons.Default.Smartphone,
                        onClick = { onVolumeClick(volume) },
                        trailing = { ChevronIcon() },
                    )
                    if (index < state.volumes.lastIndex) OneUiRowDivider()
                }
                if (state.volumes.isNotEmpty()) OneUiRowDivider()
                OneUiRow(
                    title = "Trash",
                    subtitle = if (state.trashBytes > 0uL) formatSize(state.trashBytes) else "Empty",
                    icon = Icons.Default.Delete,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onTrashClick,
                    trailing = { ChevronIcon() },
                )
            }
        }

        if (state.recent.isNotEmpty()) {
            item { OneUiSectionHeader("Recent files") }
            items(state.recent, key = { it.path }) { entry ->
                SearchResultRow(entry = entry, onClick = { onFileClick(entry) })
            }
        }

        item { Spacer(Modifier.height(OneUi.SectionGap)) }
    }
}

/**
 * The storage card. One UI leads with the used figure in large type, then a
 * segmented bar, rather than a bare percentage.
 */
@Composable
private fun StorageSummaryCard(state: HomeState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OneUi.ScreenPadding)
            .clip(OneUi.CardShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatSize(state.usedBytes),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "of ${formatSize(state.totalBytes)} used",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // A single rounded track with one segment per category, sized by
        // share of total capacity. Anything left over stays as track colour.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val total = state.totalBytes
            if (total > 0uL) {
                state.tiles.forEach { tile ->
                    val fraction = tile.bytes.toDouble() / total.toDouble()
                    // Compose rejects a zero weight, so skip slivers.
                    if (fraction > 0.001) {
                        Box(
                            Modifier
                                .weight(fraction.toFloat())
                                .fillMaxSize()
                                .background(tile.category.color()),
                        )
                    }
                }
                val used = state.tiles.sumOf { it.bytes.toDouble() }
                val free = ((total.toDouble() - used) / total.toDouble()).coerceAtLeast(0.0)
                if (free > 0.001) Box(Modifier.weight(free.toFloat()).fillMaxSize())
            }
        }

        if (state.isLoading) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Scanning…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One category: filled circle, name, then size in small grey type. */
@Composable
private fun CategoryTileItem(
    tile: CategoryTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CategoryCircle(
            icon = tile.category.icon(),
            tint = tile.category.color(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = tile.category.label(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatSize(tile.bytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}
