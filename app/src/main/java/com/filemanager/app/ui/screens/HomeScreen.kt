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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import com.filemanager.app.data.remote.RemoteServer
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.StarOutline
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
import com.filemanager.app.ui.components.color
import com.filemanager.app.ui.components.icon
import com.filemanager.app.ui.components.label
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.HomeState
import com.filemanager.app.viewmodel.Category
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.formatSize

/**
 * The landing page: a row into Recent files, a category grid, per-volume
 * storage gauges, network storage, then utilities.
 *
 * Deliberately plain rows on the page background rather than rounded grouped
 * cards - only the category tiles get a raised surface. Section headings are
 * large and white, which is what separates the sections visually.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    /** Downloads included: it opens the same kind of list as the rest. */
    onCategoryClick: (Category) -> Unit,
    onRecentClick: () -> Unit,
    onVolumeClick: (StorageVolume) -> Unit,
    onTrashClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onManageStorageClick: () -> Unit,
    /** The saved network locations, shown between Storage and Utilities. */
    remoteServers: List<RemoteServer>,
    onRemoteServerClick: (RemoteServer) -> Unit,
    onManageNetworkClick: () -> Unit,
    onFtpServerClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // A way in rather than the files themselves. They were listed at the
        // bottom, the newest ten and "View all", which made the home screen
        // long and left the way to all of them below everything else. One
        // row at the top, as My Files has it, with the whole list a tap away.
        item {
            HomeRow(
                icon = Icons.Outlined.Schedule,
                title = "Recent files",
                onClick = onRecentClick,
            )
            HorizontalDivider(
                modifier = Modifier.padding(
                    start = OneUi.ScreenPadding,
                    end = OneUi.ScreenPadding,
                    top = 12.dp,
                ),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
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
                                category = tile,
                                modifier = Modifier.weight(1f),
                                onClick = { onCategoryClick(tile) },
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
            // Shown only when the device actually has a slot: an empty slot
            // is worth reporting, a slot that does not exist is not.
            if (state.hasRemovableSlot && state.volumes.none { it.isRemovable }) {
                InsetDivider()
                HomeRow(
                    icon = Icons.Outlined.SdCard,
                    title = "SD card",
                    enabled = false,
                    trailing = { StatusPill("Not inserted") },
                )
            }
        }

        item { SectionHeading("Network") }
        item {
            // Each saved server first, then the two ways in. A server is what
            // someone came here for; managing the list is the rarer thing.
            remoteServers.forEach { server ->
                HomeRow(
                    icon = server.type.icon(),
                    title = server.label,
                    subtitle = server.summary,
                    onClick = { onRemoteServerClick(server) },
                )
                InsetDivider()
            }
            HomeRow(
                icon = Icons.Outlined.Storage,
                title = if (remoteServers.isEmpty()) "Add network storage" else "Manage network storage",
                subtitle = if (remoteServers.isEmpty()) "FTP, SFTP, SMB or WebDAV" else null,
                onClick = onManageNetworkClick,
            )
            InsetDivider()
            HomeRow(
                icon = Icons.Outlined.Wifi,
                title = "FTP server",
                subtitle = "Share this phone's files over the network",
                onClick = onFtpServerClick,
            )
        }

        item { SectionHeading("Utilities") }
        item {
            HomeRow(
                icon = Icons.Outlined.StarOutline,
                title = "Favourites",
                onClick = onFavoritesClick,
            )
            InsetDivider()
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

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/**
 * Downloads sits among the file-type tiles even though it is a folder, not a
 * type - it is the one location people reach for often enough to earn a tile,
 * and it opens the same way the others do: its files, newest first.
 */
private val HOME_TILES = listOf(
    Category.OfType(FileCategory.IMAGE),
    Category.OfType(FileCategory.VIDEO),
    Category.OfType(FileCategory.AUDIO),
    Category.OfType(FileCategory.DOCUMENT),
    Category.Downloads,
    Category.OfType(FileCategory.APK),
)

/**
 * A grid tile: a coloured outline glyph over a caption, on a lifted card.
 *
 * Installation files show the letters "APK" rather than a glyph, which is how
 * the real thing renders them and reads more clearly than an Android head.
 */
@Composable
private fun CategoryTileCard(
    category: Category,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = category.color()

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
            if (category == Category.OfType(FileCategory.APK)) {
                Text(
                    text = "APK",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            } else {
                Icon(
                    imageVector = category.icon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = category.label(),
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
            // The label sits across both the filled and unfilled parts, so
            // neither may swallow it. A translucent fill over a neutral track
            // keeps one text colour readable on both - white text on the pale
            // light-mode container was invisible.
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = formatSize(usedBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = " / ${formatSize(totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
    /** A quiet second line. The network rows use it to show where a server
     *  points, which the name alone rarely says. */
    subtitle: String? = null,
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
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
