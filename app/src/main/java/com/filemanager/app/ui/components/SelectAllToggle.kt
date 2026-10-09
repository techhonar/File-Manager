package com.filemanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.filemanager.app.ui.theme.OneUi

/**
 * The "All" control at the start of a selection's title bar, as One UI has
 * it: the same round tick the rows have, with its label underneath.
 *
 * A tick shows whether everything is already chosen and offers the opposite,
 * so the one control both selects everything and clears it.
 */
@Composable
fun SelectAllToggle(
    allSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(start = 8.dp)
            .clip(OneUi.ThumbShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (allSelected) "Deselect all" else "Select all"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SelectionCheck(checked = allSelected)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "All",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The other end of a selection's title bar: Cancel, in the text's own colour, as One UI has it. */
@Composable
fun CancelSelection(onCancel: () -> Unit) {
    TextButton(
        onClick = onCancel,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Text("Cancel")
    }
}
