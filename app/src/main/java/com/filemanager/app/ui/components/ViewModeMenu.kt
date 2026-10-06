package com.filemanager.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.filemanager.app.viewmodel.ViewMode

/**
 * List or grid, as a button in the title bar, for a list of files with no
 * menu of its own to put it in: a category, recent files. The browser has it
 * in its sort menu.
 */
@Composable
fun ViewModeMenu(current: ViewMode, onSelect: (ViewMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            if (current == ViewMode.GRID) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
            "Change view",
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        listOf(
            ViewMode.LIST to "List",
            ViewMode.DETAILED to "Detailed list",
            ViewMode.GRID to "Grid",
        ).forEach { (mode, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                trailingIcon = { if (current == mode) Icon(Icons.Default.Check, null) },
                onClick = { onSelect(mode); expanded = false },
            )
        }
    }
}
