package com.filemanager.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.OneUiGroup
import com.filemanager.app.ui.components.OneUiScreen
import com.filemanager.app.ui.components.OneUiSectionHeader
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.StorageBar
import com.filemanager.app.ui.components.StorageLegend
import com.filemanager.app.ui.theme.OneUi
import com.filemanager.app.viewmodel.StorageViewModel
import uniffi.filemanager_core.DuplicateGroup
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize

/**
 * Storage analysis: the usage breakdown, an on-demand duplicate scan, then
 * the biggest files.
 */
@Composable
fun StorageScreen(
    viewModel: StorageViewModel,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()

    OneUiScreen(title = "Storage", modifier = modifier) { padding ->
        if (state.isLoading && state.summary == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@OneUiScreen
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            state.summary?.let { summary ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OneUi.ScreenPadding)
                            .clip(OneUi.CardShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(24.dp),
                    ) {
                        Text(
                            text = formatSize(summary.totalBytes - summary.freeBytes),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "used of ${formatSize(summary.totalBytes)}  ·  " +
                                "${formatSize(summary.freeBytes)} free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        StorageBar(usage = summary.byCategory, totalBytes = summary.totalBytes)
                        Spacer(Modifier.height(24.dp))
                        StorageLegend(summary.byCategory)
                    }
                }
            }

            item { OneUiSectionHeader("Duplicate files") }
            item {
                OneUiGroup {
                    DuplicateSection(
                        isScanning = state.isScanningDuplicates,
                        progress = state.duplicateProgress,
                        groups = state.duplicates,
                        reclaimable = state.reclaimable,
                        onScan = viewModel::scanDuplicates,
                        onCancel = viewModel::cancelScan,
                        onClean = viewModel::deleteDuplicates,
                    )
                }
            }

            if (state.largest.isNotEmpty()) {
                item { OneUiSectionHeader("Largest files") }
                items(state.largest, key = { it.path }) { entry ->
                    SearchResultRow(entry = entry, onClick = { onOpenFile(entry) })
                }
            }

            item { Spacer(Modifier.height(OneUi.SectionGap)) }
        }
    }
}

@Composable
private fun DuplicateSection(
    isScanning: Boolean,
    progress: String,
    groups: List<DuplicateGroup>,
    reclaimable: ULong,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onClean: (DuplicateGroup) -> Unit,
) {
    Column(Modifier.padding(20.dp)) {
        when {
            isScanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp))
                Text(
                    text = progress.ifEmpty { "Scanning…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp).weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Stop") }
            }

            groups.isEmpty() -> {
                Text(
                    text = "Find files stored more than once. This reads file " +
                        "contents, so it takes a minute on a full device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onScan, shape = OneUi.PillShape) {
                    Text("Scan for duplicates")
                }
            }

            else -> {
                Text(
                    text = "${groups.size} duplicate sets",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${formatSize(reclaimable)} reclaimable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))

                groups.take(20).forEach { group ->
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(
                            text = group.files.first().name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${group.files.size} copies  ·  ${formatSize(group.size)} each",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onClean(group) }, shape = OneUi.PillShape) {
                                Text("Keep one, trash ${group.files.size - 1}")
                            }
                        }
                    }
                }
            }
        }
    }
}
