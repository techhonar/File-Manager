package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SizesTest {

    @Test
    fun `sizes read as the lists show them`() {
        assertEquals("0 B", formatSize(0uL))
        assertEquals("512 B", formatSize(512uL))
        assertEquals("1023 B", formatSize(1023uL))
        assertEquals("1.0 KB", formatSize(1024uL))
        assertEquals("1.5 KB", formatSize(1536uL))
        assertEquals("15 MB", formatSize(15uL * 1024uL * 1024uL))
        assertEquals("3.0 GB", formatSize(3uL * 1024uL * 1024uL * 1024uL))
        assertEquals("past the last unit, the number grows", "16384 PB", formatSize(ULong.MAX_VALUE))
    }

    @Test
    fun `a tie rounds to even, as the Rust core rounded it`() {
        // 1.25 KB and 10.5 KB exactly: String.format would say 1.3 and 11.
        assertEquals("1.2 KB", formatSize(1280uL))
        assertEquals("1.8 KB", formatSize(1792uL))
        assertEquals("10 KB", formatSize(10_752uL))
        assertEquals("12 KB", formatSize(11_776uL))
    }

    @Test
    fun `just under ten keeps its decimal`() {
        // 9.95 KB and a little: rounds up to ten, still with one decimal.
        assertEquals("10.0 KB", formatSize(10_189uL))
        assertEquals("10 KB", formatSize(10_240uL))
    }
}
