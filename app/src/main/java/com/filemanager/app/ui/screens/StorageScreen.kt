package com.filemanager.app.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.components.SearchResultRow
import com.filemanager.app.ui.components.StorageBar
import com.filemanager.app.ui.components.StorageLegend
import com.filemanager.app.viewmodel.StorageViewModel
import uniffi.filemanager_core.DuplicateGroup
import uniffi.filemanager_core.FileEntry
import uniffi.filemanager_core.formatSize

/**
 * Storage analysis: the usage breakdown, the biggest files, and an on-demand
 * duplicate scan.
 */
@Composable
fun StorageScreen(
    viewModel: StorageViewModel,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state by viewModel.state.collectAsState()

    if (state.isLoading && state.summary == null) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        state.summary?.let { summary ->
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = formatSize(summary.totalBytes - summary.freeBytes),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "used of ${formatSize(summary.totalBytes)}  ·  " +
                                "${formatSize(summary.freeBytes)} free",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        StorageBar(
                            usage = summary.byCategory,
                            totalBytes = summary.totalBytes,
                        )
                        Spacer(Modifier.height(20.dp))
                        StorageLegend(summary.byCategory)
                    }
                }
            }
        }

        item { SectionHeader("Duplicate files") }
        item {
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

        if (state.largest.isNotEmpty()) {
            item { SectionHeader("Largest files") }
            items(state.largest, key = { it.path }) { entry ->
                SearchResultRow(entry = entry, onClick = { onOpenFile(entry) })
            }
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
    Column(Modifier.padding(horizontal = 16.dp)) {
        when {
            isScanning -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(20.dp))
                    Text(
                        text = progress.ifEmpty { "Scanning…" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp).weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Stop") }
                }
            }

            groups.isEmpty() -> {
                Text(
                    text = "Find files stored more than once. This reads file " +
                        "contents, so it takes a minute on a full device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onScan) { Text("Scan for duplicates") }
            }

            else -> {
                Text(
                    text = "${groups.size} duplicate sets  ·  ${formatSize(reclaimable)} reclaimable",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))

                groups.take(20).forEach { group ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = group.files.first().name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${group.files.size} copies  ·  ${formatSize(group.size)} each",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onClean(group) }) {
                                Text("Keep one, trash ${group.files.size - 1}")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}
