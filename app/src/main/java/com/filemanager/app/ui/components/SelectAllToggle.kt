package com.filemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The "All" control in a selection bar: a checkbox with a visible label.
 *
 * A bare icon was too easy to miss, and it gave no clue whether everything was
 * already selected. A checkbox shows the state and offers the opposite action,
 * so the same control both selects and clears.
 */
@Composable
fun SelectAllToggle(
    allSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 12.dp)
            .semantics {
                contentDescription = if (allSelected) "Deselect all" else "Select all"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = allSelected, onCheckedChange = { onToggle() })
        Text(
            text = "All",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
