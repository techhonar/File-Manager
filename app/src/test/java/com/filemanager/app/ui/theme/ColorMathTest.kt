package com.filemanager.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The colour picker's conversions between hex, ARGB and HSV. */
class ColorMathTest {

    @Test
    fun `hex round-trips, with or without the hash`() {
        assertEquals(0xFF0F6FDE.toInt(), parseHexColor("#0F6FDE"))
        assertEquals(0xFF0F6FDE.toInt(), parseHexColor("0f6fde"))
        assertEquals("#0F6FDE", hexOf(0xFF0F6FDE.toInt()))
    }

    @Test
    fun `the short form stands for doubled digits`() {
        assertEquals(0xFFFFAA00.toInt(), parseHexColor("#FA0"))
    }

    @Test
    fun `half-typed or wrong text is not a colour`() {
        assertNull(parseHexColor("#0F6F"))
        assertNull(parseHexColor("#GGGGGG"))
        assertNull(parseHexColor(""))
    }

    @Test
    fun `primary colours convert to their hues`() {
        assertHsv(0f, 1f, 1f, argbToHsv(0xFFFF0000.toInt()))
        assertHsv(120f, 1f, 1f, argbToHsv(0xFF00FF00.toInt()))
        assertHsv(240f, 1f, 1f, argbToHsv(0xFF0000FF.toInt()))
        assertHsv(0f, 0f, 0.5f, argbToHsv(0xFF808080.toInt()), tolerance = 0.01f)
    }

    @Test
    fun `hsv and back gives the same colour`() {
        listOf(0xFF0F6FDE, 0xFFC2255C, 0xFF2E7D32, 0xFF000000, 0xFFFFFFFF, 0xFF7B3FE4, 0xFFB8520A)
            .map { it.toInt() }
            .forEach { argb ->
                val (h, s, v) = argbToHsv(argb).toList()
                assertEquals(hexOf(argb), hexOf(hsvToArgb(h, s, v)))
            }
    }

    private fun assertHsv(h: Float, s: Float, v: Float, actual: FloatArray, tolerance: Float = 0.001f) {
        assertEquals(h, actual[0], tolerance)
        assertEquals(s, actual[1], tolerance)
        assertEquals(v, actual[2], tolerance)
    }
}
