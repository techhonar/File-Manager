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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filemanager.app.data.StorageVolume
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.icon
import com.filemanager.app.ui.components.label
import com.filemanager.app.ui.theme.CategoryColors
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.HomeState
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize

/**
 * The landing page: Recent files, a category grid, per-volume storage gauges,
 * then utilities.
 *
 * Deliberately plain rows on the page background rather than rounded grouped
 * cards - only the category tiles get a raised surface. Section headings are
 * large and white, which is what separates the sections visually.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onCategoryClick: (FileCategory) -> Unit,
    onDownloadsClick: () -> Unit,
    onRecentClick: () -> Unit,
    onVolumeClick: (StorageVolume) -> Unit,
    onTrashClick: () -> Unit,
    onManageStorageClick: () -> Unit,
    onFileClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            HomeRow(
                icon = Icons.Outlined.Schedule,
                title = "Recent files",
                onClick = onRecentClick,
            )
            InsetDivider()
        }

        item { SectionHeading("Categories") }
        item {
            Column(
                modifier = Modifier.padding(horizontal = OneUi.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HOME_TILES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { tile ->
                            CategoryTileCard(
                                tile = tile,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    when (tile) {
                                        is HomeTile.Category -> onCategoryClick(tile.category)
                                        HomeTile.Downloads -> onDownloadsClick()
                                    }
                                },
                            )
                        }
                        // Pad a short final row so tiles keep their width
                        // instead of stretching to fill it.
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item { SectionHeading("Storage") }
        item {
            state.volumes.forEachIndexed { index, volume ->
                val usage = state.volumeUsage[volume.path]
                HomeRow(
                    icon = if (volume.isRemovable) Icons.Outlined.SdCard
                    else Icons.Outlined.Smartphone,
                    title = volume.name,
                    onClick = { onVolumeClick(volume) },
                    trailing = {
                        if (usage != null && usage.totalBytes > 0uL) {
                            StorageGauge(usage.usedBytes, usage.totalBytes)
                        }
                    },
                )
                if (index < state.volumes.lastIndex) InsetDivider()
            }
            // One UI always shows the SD card slot, saying so when empty,
            // rather than hiding the row and leaving the user to wonder.
            if (state.volumes.none { it.isRemovable }) {
                InsetDivider()
                HomeRow(
                    icon = Icons.Outlined.SdCard,
                    title = "SD card",
                    enabled = false,
                    trailing = { StatusPill("Not inserted") },
                )
            }
        }

        item { SectionHeading("Utilities") }
        item {
            HomeRow(
                icon = Icons.Outlined.Delete,
                title = "Trash",
                onClick = onTrashClick,
                trailing = {
                    if (state.trashBytes > 0uL) {
                        Text(
                            text = formatSize(state.trashBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            InsetDivider()
            HomeRow(
                icon = Icons.Outlined.PieChart,
                title = "Manage storage",
                onClick = onManageStorageClick,
            )
        }

        if (state.recent.isNotEmpty()) {
            item { SectionHeading("Recent") }
            items(state.recent.take(10), key = { it.path }) { entry ->
                SearchResultRow(entry = entry, onClick = { onFileClick(entry) })
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** What a tile on the category grid can point at. */
private sealed interface HomeTile {
    data class Category(val category: FileCategory) : HomeTile
    data object Downloads : HomeTile
}

/**
 * Downloads sits among the file-type tiles even though it is a folder, not a
 * type - it is the one location people reach for often enough to earn a tile.
 */
private val HOME_TILES = listOf(
    HomeTile.Category(FileCategory.IMAGE),
    HomeTile.Category(FileCategory.VIDEO),
    HomeTile.Category(FileCategory.AUDIO),
    HomeTile.Category(FileCategory.DOCUMENT),
    HomeTile.Downloads,
    HomeTile.Category(FileCategory.APK),
)

/**
 * A grid tile: a coloured outline glyph over a caption, on a lifted card.
 *
 * Installation files show the letters "APK" rather than a glyph, which is how
 * the real thing renders them and reads more clearly than an Android head.
 */
@Composable
private fun CategoryTileCard(
    tile: HomeTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (tile) {
        is HomeTile.Category -> tile.category.color()
        HomeTile.Downloads -> CategoryColors.Downloads
    }
    val caption = when (tile) {
        is HomeTile.Category -> tile.category.label()
        HomeTile.Downloads -> "Downloads"
    }

    Box(
        modifier = modifier
            .aspectRatio(1.28f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (tile is HomeTile.Category && tile.category == FileCategory.APK) {
                Text(
                    text = "APK",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            } else {
                val glyph: ImageVector = when (tile) {
                    is HomeTile.Category -> tile.category.icon()
                    HomeTile.Downloads -> Icons.Outlined.Download
                }
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * The capacity gauge on a storage row: a pill whose fill shows the used
 * fraction, with the figures written across it.
 */
@Composable
private fun StorageGauge(usedBytes: ULong, totalBytes: ULong) {
    val fraction = (usedBytes.toDouble() / totalBytes.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()

    Box(
        modifier = Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 150.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formatSize(usedBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = " / ${formatSize(totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

/** Pill used where there are no figures to show, such as an empty SD slot. */
@Composable
private fun StatusPill(text: String) {
    Box(
        modifier = Modifier
            .height(40.dp)
            .defaultMinSize(minWidth = 150.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Large white section heading. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(
            start = OneUi.ScreenPadding,
            end = OneUi.ScreenPadding,
            top = 28.dp,
            bottom = 16.dp,
        ),
    )
}

/** A plain row on the page: outline glyph, title, optional trailing slot. */
@Composable
private fun HomeRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tint = if (enabled) MaterialTheme.colorScheme.onBackground
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .defaultMinSize(minHeight = 72.dp)
            .padding(horizontal = OneUi.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** Divider inset to line up with the row text rather than the icon. */
@Composable
private fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = OneUi.ScreenPadding + 50.dp, end = OneUi.ScreenPadding),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
