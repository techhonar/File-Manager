package com.filemanager.app.ui.components

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class FileGridTest {

    @Test
    fun `a phone held upright shows exactly the number chosen`() {
        for (perRow in 3..5) {
            for (width in listOf(320f, 360f, 393f, 411f, 430f, 480f)) {
                assertEquals("$perRow across ${width}dp", perRow, gridColumnCount(width, perRow))
            }
        }
    }

    @Test
    fun `a wider screen adds columns rather than stretching the tiles`() {
        assertEquals("a phone on its side", 6, gridColumnCount(880f, 3))
        assertEquals(10, gridColumnCount(880f, 5))
        assertEquals("a small tablet", 4, gridColumnCount(600f, 3))
        assertEquals("never fewer than chosen, just past a phone's width", 5, gridColumnCount(481f, 5))
    }

    @Test
    fun `the columns fill the row to the pixel`() {
        val sizes = with(FileGridCells(4)) { Density(1f).calculateCrossAxisCellSizes(410, 0) }
        assertEquals(listOf(103, 103, 102, 102), sizes)

        val spaced = with(FileGridCells(3)) { Density(2f).calculateCrossAxisCellSizes(1000, 8) }
        assertEquals(3, spaced.size)
        assertEquals("tiles and gaps make the whole row", 1000, spaced.sum() + 8 * 2)
    }
}
