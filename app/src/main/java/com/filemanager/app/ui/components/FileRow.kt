package com.filemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One row in a file list.
 *
 * Long-press starts selection mode, which is the gesture every Android file
 * manager uses -- including Samsung's.
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(4.dp))
        }

        FileThumbnail(entry)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
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
 * Real thumbnail for images and video, category icon for everything else.
 *
 * Coil reads the file directly from its path and caches decoded bitmaps, so
 * scrolling a folder of 2,000 photos does not decode them all.
 */
@Composable
private fun FileThumbnail(entry: FileEntry, size: Int = 44) {
    val showsPreview = entry.category == FileCategory.IMAGE || entry.category == FileCategory.VIDEO

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (showsPreview) Color.Transparent
                else entry.category.color().copy(alpha = 0.12f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (showsPreview) {
            AsyncImage(
                model = entry.path,
                contentDescription = null,
                modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Icon(
                imageVector = entry.category.icon(),
                contentDescription = null,
                tint = entry.category.color(),
                modifier = Modifier.size((size * 0.55).dp),
            )
        }
    }
}

/** "12 Sep 2026  ·  4.2 MB", or just the date for a folder. */
private fun FileEntry.subtitle(): String {
    val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date(modifiedMs.toLong()))
    return if (isDir) date else "$date  ·  ${formatSize(size)}"
}

/** A compact row used by search results, which also shows the parent folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchResultRow(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        FileThumbnail(entry, size = 40)
        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
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
        Text(
            text = formatSize(entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
