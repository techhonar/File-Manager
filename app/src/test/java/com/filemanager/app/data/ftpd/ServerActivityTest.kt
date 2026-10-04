package com.filemanager.app.data.ftpd

import com.filemanager.app.data.remote.FtpRemoteClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread
import kotlin.random.Random

/** What the built-in server reports about the files going through it. */
class ServerActivityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val activity = ServerActivity(progressIntervalMs = 0)
    private var server: TestFtpServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    private fun start(root: File, bytesPerSecond: Int = 0): FtpRemoteClient {
        val running = TestFtpServer(root, bytesPerSecond, activity = activity).also { server = it }
        return FtpRemoteClient(running.remote)
    }

    /** The first report on a transfer under way that [accept] takes. */
    private fun watchFor(accept: (ServerTransfer) -> Boolean): ServerTransfer {
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            activity.transfers.value.firstOrNull(accept)?.let { return it }
            Thread.sleep(5)
        }
        error("never saw it; last saw ${activity.transfers.value}")
    }

    @Test
    fun `a file being received is counted as it arrives`() {
        val root = temp.newFolder("served")
        val local = temp.newFile("big.bin").apply { writeBytes(Random(1).nextBytes(256 * 1024)) }
        val client = start(root, bytesPerSecond = 64 * 1024)

        val upload = thread { client.use { it.upload(local, "/big.bin") } }
        val seen = watchFor { it.bytes > 0 }
        upload.join()

        assertEquals("big.bin", seen.name)
        assertTrue(seen.receiving)
        assertEquals("FTP sends no size ahead of a file", -1L, seen.total)
        assertTrue(seen.bytes < local.length())
        assertTrue("it was still listed once it had arrived", activity.transfers.value.isEmpty())
        assertEquals(local.length(), File(root, "big.bin").length())
    }

    @Test
    fun `a file being sent is counted against its size`() {
        val root = temp.newFolder("served")
        File(root, "big.bin").writeBytes(Random(2).nextBytes(256 * 1024))
        val client = start(root, bytesPerSecond = 64 * 1024)
        val target = File(temp.root, "got.bin")

        val download = thread { client.use { it.download("/big.bin", target) } }
        val seen = watchFor { it.bytes > 0 }
        download.join()

        assertEquals("big.bin", seen.name)
        assertTrue(!seen.receiving)
        assertEquals(256L * 1024, seen.total)
        assertTrue(activity.transfers.value.isEmpty())
    }

    @Test
    fun `renaming and listing still work through the counted files`() {
        // The library's own files cast a rename's destination to their own
        // type; handed a wrapper, the rename would fail.
        val root = temp.newFolder("served")
        File(root, "old.txt").writeText("x")
        start(root).use { client ->
            client.rename("/old.txt", "/new.txt")
            assertEquals(listOf("new.txt"), client.list("/").map { it.name })
        }
        assertTrue(File(root, "new.txt").exists())
    }

    @Test
    fun `the notification says what is moving and how far`() {
        val size = { bytes: Long -> "${bytes}B" }
        val receiving = ServerTransfer(1, "photo.jpg", receiving = true, bytes = 40, total = -1)
        val sending = ServerTransfer(2, "report.pdf", receiving = false, bytes = 25, total = 100)

        assertEquals("Receiving photo.jpg · 40B", describeServerTransfers(listOf(receiving), size))
        assertEquals("Sending report.pdf · 25B of 100B", describeServerTransfers(listOf(sending), size))
        assertEquals(
            "Receiving 1 file, sending 1 file · 65B",
            describeServerTransfers(listOf(receiving, sending), size),
        )
        assertEquals(
            "Receiving 2 files · 80B",
            describeServerTransfers(listOf(receiving, receiving.copy(id = 3)), size),
        )

        assertEquals(0.25f, serverTransferFraction(listOf(sending)))
        assertNull("no size, no fraction", serverTransferFraction(listOf(receiving, sending)))
        assertNull(serverTransferFraction(emptyList()))
    }
}
