package com.filemanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import uniffi.filemanager_core.CategoryUsage
import uniffi.filemanager_core.formatSize

/**
 * The stacked usage bar: one segment per category, widest first, with the
 * unaccounted remainder left as track colour.
 */
@Composable
fun StorageBar(
    usage: List<CategoryUsage>,
    totalBytes: ULong,
    modifier: Modifier = Modifier,
) {
    if (totalBytes == 0uL) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        usage.filter { it.bytes > 0uL }.forEach { slice ->
            // Compose rejects a zero weight, so a category rounding to no
            // width is skipped rather than crashing the layout.
            val fraction = slice.bytes.toDouble() / totalBytes.toDouble()
            if (fraction > 0.001) {
                Box(
                    Modifier
                        .weight(fraction.toFloat())
                        .fillMaxSize()
                        .background(slice.category.color()),
                )
            }
        }
        val used = usage.sumOf { it.bytes.toDouble() }
        val free = ((totalBytes.toDouble() - used) / totalBytes.toDouble()).coerceAtLeast(0.0)
        if (free > 0.001) {
            Box(Modifier.weight(free.toFloat()).fillMaxSize())
        }
    }
}

/** The colour key under the bar: a filled dot, the name, then the figures. */
@Composable
fun StorageLegend(usage: List<CategoryUsage>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        usage.filter { it.bytes > 0uL }.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(slice.category.color()),
                )
                Text(
                    text = slice.category.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 14.dp).weight(1f),
                )
                Text(
                    text = formatSize(slice.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${slice.fileCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
