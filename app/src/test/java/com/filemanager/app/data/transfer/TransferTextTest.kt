package com.filemanager.app.data.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferTextTest {

    private val size = { bytes: Long -> "${bytes}B" }

    private fun transfer(
        id: Int = 1,
        direction: Direction = Direction.DOWNLOAD,
        files: Int = 1,
        done: Int = 0,
        current: String? = "report.pdf",
        bytes: Long = 25,
        total: Long = 100,
    ) = Transfer(
        id = id,
        direction = direction,
        serverId = "nas",
        serverLabel = "NAS",
        destination = "Download",
        fileCount = files,
        filesDone = done,
        current = current,
        bytesDone = bytes,
        bytesTotal = total,
    )

    @Test
    fun `one file is named, several are counted`() {
        assertEquals("Downloading report.pdf from NAS", transfersTitle(listOf(transfer())))
        val upload = transfer(direction = Direction.UPLOAD, files = 5)
        assertEquals("Uploading 5 files to NAS", transfersTitle(listOf(upload)))
        assertEquals("2 transfers", transfersTitle(listOf(transfer(), transfer(id = 2))))
    }

    @Test
    fun `the text says how much, and which file of a batch`() {
        assertEquals("25B of 100B", transfersText(listOf(transfer()), size))
        assertEquals("25B of 100B · 3 of 5", transfersText(listOf(transfer(files = 5, done = 2)), size))
        assertEquals(
            "the last file is never 'six of five'",
            "100B of 100B · 5 of 5",
            transfersText(listOf(transfer(files = 5, done = 5, bytes = 100)), size),
        )
        assertEquals("50B of 200B", transfersText(listOf(transfer(), transfer(id = 2)), size))
        assertEquals("sizes unknown", "25B", transfersText(listOf(transfer(total = 0)), size))
    }

    @Test
    fun `progress is the share of every byte`() {
        assertEquals(0.25f, transfersFraction(listOf(transfer())))
        assertEquals(0.5f, transfersFraction(listOf(transfer(), transfer(id = 2, bytes = 75))))
        assertNull(transfersFraction(listOf(transfer(total = 0))))
        assertEquals(0.25f, transfer().fraction)
        assertNull(transfer(total = 0).fraction)
    }
}
