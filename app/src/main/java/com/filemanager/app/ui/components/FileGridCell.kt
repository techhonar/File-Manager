package com.filemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.filemanager.app.ui.theme.OneUi
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize

/**
 * One tile in grid view: a large square preview with the name beneath.
 *
 * Grid view exists for pictures and video, so images fill the tile and are
 * cropped to it rather than letterboxed - a wall of thumbnails with matching
 * edges is far easier to scan than one with ragged gaps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridCell(
    entry: FileEntry,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Video frames and installer icons are loaded by decoders registered on
    // the app's ImageLoader; without those this would draw nothing for them.
    val showsPreview = entry.category == FileCategory.IMAGE ||
        entry.category == FileCategory.VIDEO ||
        entry.category == FileCategory.APK

    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(OneUi.ThumbShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (showsPreview) {
                AsyncImage(
                    model = entry.path,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(OneUi.ThumbShape),
                )
            } else {
                Icon(
                    imageVector = entry.category.icon(),
                    contentDescription = null,
                    tint = entry.category.color(),
                    modifier = Modifier.size(40.dp),
                )
            }

            if (selectionMode) {
                Box(Modifier.fillMaxSize().padding(4.dp), Alignment.TopStart) {
                    Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (entry.isDir) "Folder" else formatSize(entry.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
    }
}
