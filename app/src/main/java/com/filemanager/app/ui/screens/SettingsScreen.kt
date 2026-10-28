package com.filemanager.app.ui.screens

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.filemanager.app.data.AppSettings
import com.filemanager.app.ui.components.OneUiGroup
import com.filemanager.app.ui.components.OneUiRowDivider
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.OneUiSectionHeader
import com.filemanager.app.ui.theme.LocalCategoryPalette
import com.filemanager.app.ui.theme.OneUi

/** The grid sizes on offer, by how many tiles fit in a row on a phone. */
private val GridSizes = listOf(3 to "Large", 4 to "Medium", 5 to "Small")

/**
 * The app's settings. For now the size of the tiles in grid view; how the
 * app looks has a screen of its own, Theme.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val columns by settings.gridColumns.collectAsState()

    OneUiScreen(
        title = "Settings",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            OneUiSectionHeader("Grid size")
            Text(
                text = "How many items fit in a row in grid view - in folders, categories, search and recent files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = OneUi.ScreenPadding + 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            OneUiGroup {
                GridSizes.forEachIndexed { index, (perRow, name) ->
                    if (index > 0) OneUiRowDivider(inset = false)
                    ChoiceRow(
                        title = name,
                        subtitle = "$perRow in a row",
                        selected = columns == perRow,
                    ) { settings.setGridColumns(perRow) }
                }
            }
            Spacer(Modifier.height(12.dp))
            OneUiGroup {
                GridPreview(columns, Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Two rows of tiles at the size chosen, so the choice can be seen before
 * going back to a grid. The tiles take the category colours, as files do.
 */
@Composable
private fun GridPreview(perRow: Int, modifier: Modifier = Modifier) {
    val shown by animateIntAsState(perRow, label = "previewColumns")
    val palette = LocalCategoryPalette.current
    val colours = listOf(palette.image, palette.video, palette.audio, palette.document, palette.archive, palette.apk)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(shown) { column ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colours[(row * shown + column) % colours.size].copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}
