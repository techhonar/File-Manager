package com.filemanager.app.data.remote

import com.filemanager.app.data.ftpd.FtpServerConfig
import com.filemanager.app.data.ftpd.FtpServerController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * The app's FTP client against the app's own FTP server.
 *
 * Both halves of this feature ship together, so testing them against each
 * other costs one process and covers both. It is also the only way either is
 * checked at all without a server on the network - and it has already earned
 * its keep: the server refused every login until it was given a
 * ConcurrentLoginPermission, which nothing about the API suggests is required.
 */
class FtpRoundTripTest {

    private lateinit var root: File
    private val controller = FtpServerController()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ftp-test").toFile()
    }

    @After
    fun tearDown() {
        controller.stop()
        root.deleteRecursively()
    }

    /** A port nothing is on, rather than a fixed one that CI might be using. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(readOnly: Boolean = false, anonymous: Boolean = false): Int {
        val port = freePort()
        controller.start(
            FtpServerConfig(
                port = port,
                username = "tester",
                password = "s3cret",
                anonymous = anonymous,
                readOnly = readOnly,
                rootPath = root.absolutePath,
            ),
        )
        return port
    }

    private fun client(port: Int, anonymous: Boolean = false, password: String = "s3cret") =
        FtpRemoteClient(
            RemoteServer(
                type = RemoteType.FTP,
                host = "127.0.0.1",
                port = port,
                username = "tester",
                password = password,
                anonymous = anonymous,
            ),
        )

    @Test
    fun `lists what the server is serving`() {
        File(root, "photos").mkdirs()
        File(root, "notes.txt").writeText("hello from the server")

        client(start()).use { ftp ->
            val entries = ftp.list("/")
            assertEquals(setOf("photos", "notes.txt"), entries.map { it.name }.toSet())
            assertTrue(entries.first { it.name == "photos" }.isDir)
            assertEquals(21L, entries.first { it.name == "notes.txt" }.size)
            assertEquals("/notes.txt", entries.first { it.name == "notes.txt" }.path)
        }
    }

    @Test
    fun `downloads and uploads`() {
        File(root, "notes.txt").writeText("hello from the server")
        val port = start()

        client(port).use { ftp ->
            val got = File(root.parentFile, "got-${System.nanoTime()}.txt")
            ftp.download("/notes.txt", got)
            assertEquals("hello from the server", got.readText())
            got.delete()

            val send = File(root.parentFile, "send-${System.nanoTime()}.txt")
                .apply { writeText("from the phone") }
            ftp.upload(send, "/uploaded.txt")
            assertEquals("from the phone", File(root, "uploaded.txt").readText())
            send.delete()
        }
    }

    @Test
    fun `creates renames and deletes`() {
        client(start()).use { ftp ->
            ftp.makeDirectory("/made")
            assertTrue(File(root, "made").isDirectory)

            ftp.rename("/made", "/renamed")
            assertTrue(File(root, "renamed").isDirectory)
            assertFalse(File(root, "made").exists())

            ftp.delete("/renamed", isDir = true)
            assertFalse(File(root, "renamed").exists())
        }
    }

    @Test
    fun `read only refuses to change anything`() {
        File(root, "readable.txt").writeText("you may read this")
        val port = start(readOnly = true)

        client(port).use { ftp ->
            // Reading still works, which is the point of the mode.
            assertEquals(1, ftp.list("/").size)
            val got = File(root.parentFile, "ro-${System.nanoTime()}.txt")
            ftp.download("/readable.txt", got)
            assertEquals("you may read this", got.readText())
            got.delete()

            val send = File(root.parentFile, "nope-${System.nanoTime()}.txt")
                .apply { writeText("x") }
            assertThrows { ftp.upload(send, "/nope.txt") }
            assertFalse("nothing should have been written", File(root, "nope.txt").exists())

            assertThrows { ftp.delete("/readable.txt", isDir = false) }
            assertTrue("the file should survive", File(root, "readable.txt").exists())
            send.delete()
        }
    }

    @Test
    fun `anonymous can connect when it is allowed`() {
        File(root, "public.txt").writeText("open to all")
        val port = start(readOnly = true, anonymous = true)

        client(port, anonymous = true).use { ftp ->
            assertEquals(listOf("public.txt"), ftp.list("/").map { it.name })
        }
    }

    @Test
    fun `the wrong password is refused`() {
        val port = start()
        assertThrows { client(port, password = "wrong").use { it.list("/") } }
    }

    @Test
    fun `the controller reports what it is running`() {
        val port = start()
        assertTrue(controller.isRunning)
        assertEquals(port, controller.running.value?.port)

        controller.stop()
        assertFalse(controller.isRunning)
        assertEquals(null, controller.running.value)
    }

    private fun assertThrows(body: () -> Unit) {
        val result = runCatching(body)
        assertTrue("expected this to fail, but it succeeded", result.isFailure)
    }
}
