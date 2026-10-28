package com.filemanager.app.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Density
import com.filemanager.app.data.DEFAULT_GRID_COLUMNS

/**
 * How many tiles a grid shows across a phone held upright, as chosen in
 * Settings. Provided at the top of the app, from AppSettings.gridColumns.
 */
val LocalGridColumns = compositionLocalOf { DEFAULT_GRID_COLUMNS }

/**
 * [perRow] tiles across a phone held upright, as Settings says, and on
 * anything wider - a tablet, or the phone turned on its side - as many more
 * as keep the tiles about that size, where a fixed count would make them
 * enormous.
 */
data class FileGridCells(val perRow: Int) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = gridColumnCount(availableSize.toDp().value, perRow)
        val usable = availableSize - spacing * (count - 1)
        val each = usable / count
        val left = usable % count
        // The pixels that do not divide evenly go one each to the first
        // columns, so the row fills its width exactly.
        return List(count) { each + if (it < left) 1 else 0 }
    }
}

/** The tiles across a grid [widthDp] wide: [perRow] on a phone, more on anything wider. */
fun gridColumnCount(widthDp: Float, perRow: Int): Int =
    if (widthDp <= WIDEST_PHONE_DP) perRow else maxOf(perRow, (widthDp * perRow / TYPICAL_PHONE_DP).toInt())

/** The widest a phone held upright gets; past it, the grid adds columns. */
private const val WIDEST_PHONE_DP = 480f

/** What the count in Settings means: that many across a phone this wide. */
private const val TYPICAL_PHONE_DP = 412f
