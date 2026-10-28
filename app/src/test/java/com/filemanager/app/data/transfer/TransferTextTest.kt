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

    @Test
    fun `a copy or move says what was asked for, as One UI words it`() {
        assertEquals("Copying file…", placingTitle(transfer(direction = Direction.COPY)))
        assertEquals("Copying 12 files…", placingTitle(transfer(direction = Direction.COPY, files = 12)))
        assertEquals("Moving file…", placingTitle(transfer(direction = Direction.MOVE)))
        assertEquals("Copying 3 files…", transfersTitle(listOf(transfer(direction = Direction.COPY, files = 3))))
        val upload = transfer(direction = Direction.UPLOAD, files = 3).copy(isMove = true)
        assertEquals("a move to a server is still a move", "Moving 3 files to NAS…", placingTitle(upload))
        assertEquals("Copying file to NAS…", placingTitle(transfer(direction = Direction.UPLOAD)))
    }

    @Test
    fun `how a copy or move ended`() {
        assertEquals("Copy completed.", placeSummary(move = false, items = 3, placed = 3, stopped = false, failure = null))
        assertEquals("Move completed.", placeSummary(move = true, items = 1, placed = 1, stopped = false, failure = null))
        assertEquals(
            "File transfer cancelled. File not copied.",
            placeSummary(move = false, items = 1, placed = 0, stopped = true, failure = null),
        )
        assertEquals(
            "File transfer cancelled. Files not moved.",
            placeSummary(move = true, items = 4, placed = 0, stopped = true, failure = null),
        )
        assertEquals(
            "File transfer cancelled. 2 of 5 copied.",
            placeSummary(move = false, items = 5, placed = 2, stopped = true, failure = null),
        )
        assertEquals(
            "Could not move: Permission denied",
            placeSummary(move = true, items = 2, placed = 0, stopped = false, failure = "Permission denied"),
        )
        assertEquals(
            "1 of 2 copied. Could not copy the rest: disk full",
            placeSummary(move = false, items = 2, placed = 1, stopped = false, failure = "disk full"),
        )
    }
}
