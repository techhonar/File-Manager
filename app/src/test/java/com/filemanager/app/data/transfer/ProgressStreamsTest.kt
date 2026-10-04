package com.filemanager.app.data.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ProgressStreamsTest {

    @Test
    fun `reading counts every byte, however it is read`() {
        val data = Random(1).nextBytes(10_000)
        val seen = mutableListOf<Long>()
        val copy = ProgressInputStream(ByteArrayInputStream(data)) { seen += it }.use { input ->
            val first = input.read()
            val skipped = input.skip(9)
            val rest = input.readBytes()
            assertEquals(data[0].toInt() and 0xff, first)
            assertEquals(9L, skipped)
            rest
        }
        assertArrayEquals(data.copyOfRange(10, data.size), copy)
        assertEquals(10_000L, seen.last())
        assertTrue("never counts backwards", seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `writing counts every byte and passes buffers on whole`() {
        val data = Random(2).nextBytes(10_000)
        val seen = mutableListOf<Long>()
        val sink = ByteArrayOutputStream()
        ProgressOutputStream(sink) { seen += it }.use { output ->
            output.write(data[0].toInt())
            output.write(data, 1, data.size - 1)
        }
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(listOf(1L, 10_000L), seen)
    }

    @Test
    fun `throwing from the count stops the copy`() {
        val data = ByteArray(100_000)
        val sink = ByteArrayOutputStream()
        var stopped = false
        try {
            val stopAt20k = { bytes: Long -> if (bytes > 20_000) throw TransferCancelledException() }
            ProgressInputStream(ByteArrayInputStream(data), stopAt20k).use { it.copyTo(sink) }
        } catch (e: TransferCancelledException) {
            stopped = true
        }
        assertTrue(stopped)
        assertTrue("it went on after being stopped", sink.size() < data.size)
    }

    @Test
    fun `a pacer lets one through, then holds the rest back for its interval`() {
        val pacer = Pacer(intervalMs = 60_000)
        assertTrue(pacer.due())
        assertFalse(pacer.due())
        assertFalse(pacer.due())
        assertTrue("no interval, no holding back", Pacer(0).let { it.due() && it.due() })
    }
}
