package com.filemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.filemanager.app.ui.theme.OneUi
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One row in a file list.
 *
 * Long-press starts selection mode, the gesture every Android file manager
 * uses. Rows are deliberately tall - One UI favours large touch targets over
 * fitting more on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: FileEntry,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = OneUi.RowHeight)
            .padding(horizontal = OneUi.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        }

        FileThumbnail(entry)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = entry.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Real thumbnail for images and video, a filled category circle otherwise.
 *
 * Coil reads the file straight from its path and caches decoded bitmaps, so
 * scrolling a folder of 2,000 photos does not decode them all.
 */
@Composable
private fun FileThumbnail(entry: FileEntry, size: Int = 46) {
    // Video frames and installer icons are loaded by decoders registered on
    // the app's ImageLoader; without those this would draw nothing for them.
    val showsPreview = entry.category == FileCategory.IMAGE ||
        entry.category == FileCategory.VIDEO ||
        entry.category == FileCategory.APK ||
        entry.category == FileCategory.AUDIO

    if (showsPreview) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(OneUi.ThumbShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = entry.path,
                contentDescription = null,
                modifier = Modifier.size(size.dp).clip(OneUi.ThumbShape),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(entry.category.color()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = entry.category.icon(),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size((size * 0.5).dp),
            )
        }
    }
}

/** "12 Sep 2026 · 4.2 MB", or just the date for a folder. */
private fun FileEntry.subtitle(): String {
    val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date(modifiedMs.toLong()))
    return if (isDir) date else "$date  ·  ${formatSize(size)}"
}

/** Row used by search results and Recent files, which also shows the folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultRow(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = OneUi.RowHeight)
            .padding(horizontal = OneUi.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        }
        FileThumbnail(entry, size = 44)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Where the file lives matters more than its date in results.
                text = entry.path.substringBeforeLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatSize(entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Detailed list row: the same as [FileRow] plus the type and a fuller
 * timestamp, for comparing files at a glance rather than reading one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileDetailRow(
    entry: FileEntry,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = 84.dp)
            .padding(horizontal = OneUi.ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        }

        FileThumbnail(entry)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDir) "Folder" else entry.category.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = detailedTimestamp(entry) +
                    if (entry.isDir) "" else "  ·  ${formatSize(entry.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun detailedTimestamp(entry: FileEntry): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        .format(Date(entry.modifiedMs.toLong()))
