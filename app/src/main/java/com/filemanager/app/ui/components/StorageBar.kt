package com.filemanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import uniffi.filemanager_core.FileCategory
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
    /** Sweeps the fill from nothing to its full width when it reaches 1. */
    progress: Float = 1f,
) {
    if (totalBytes == 0uL) return

    val segments = usage.filter { it.bytes > 0uL }
    val usedFraction = segments
        .sumOf { it.bytes.toDouble() / totalBytes.toDouble() }
        .coerceIn(0.0, 1.0)
        .toFloat()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // The whole used portion is one box scaled by progress, with the
        // categories laid out inside it. Animating each segment separately
        // would have them arrive at different times and reflow as they grew.
        Row(
            Modifier
                .fillMaxWidth(usedFraction * progress)
                .fillMaxHeight(),
        ) {
            segments.forEach { slice ->
                // Compose rejects a zero weight, so a category rounding to no
                // width is skipped rather than crashing the layout.
                val share = slice.bytes.toDouble() / totalBytes.toDouble()
                if (share > 0.001) {
                    Box(
                        Modifier
                            .weight(share.toFloat())
                            .fillMaxSize()
                            .background(slice.category.color()),
                    )
                }
            }
        }
    }
}

/**
 * Every category the app knows about, in the order the legend lists them.
 *
 * Fixed rather than derived from a result, so the legend can be drawn in full
 * before anything has been measured - the layout never reflows as figures
 * arrive, because the rows were already there.
 */
private val LEGEND_ORDER = listOf(
    FileCategory.IMAGE,
    FileCategory.VIDEO,
    FileCategory.AUDIO,
    FileCategory.DOCUMENT,
    FileCategory.ARCHIVE,
    FileCategory.APK,
    FileCategory.OTHER,
)

/**
 * The colour key under the bar: a filled dot, the name, then the figures.
 *
 * With [usage] empty the names and colours are still shown and the figures are
 * simply blank, which is what a scan in progress should look like - no zeroes
 * that will change, and nothing moving when the real numbers land.
 */
@Composable
fun StorageLegend(usage: List<CategoryUsage>, modifier: Modifier = Modifier) {
    if (usage.isEmpty()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LEGEND_ORDER.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(category.color()),
                    )
                    Text(
                        text = category.label(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 14.dp).weight(1f),
                    )
                    // Deliberately nothing here yet.
                }
            }
        }
        return
    }

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
