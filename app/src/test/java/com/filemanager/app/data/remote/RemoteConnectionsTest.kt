package com.filemanager.app.data.remote

import com.filemanager.app.data.ftpd.FtpServerConfig
import com.filemanager.app.data.ftpd.FtpServerController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The connection pool under the use the remote browser actually makes of it.
 *
 * A transfer shows its progress without blocking the list, so the user can go
 * on browsing while a file comes down - which means two operations on the same
 * server at once. An FTP connection carries one conversation at a time.
 */
class RemoteConnectionsTest {

    private lateinit var root: File
    private lateinit var scratch: File
    private val controller = FtpServerController()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ftp-pool").toFile()
        scratch = Files.createTempDirectory("ftp-pool-local").toFile()
    }

    @After
    fun tearDown() {
        controller.stop()
        root.deleteRecursively()
        scratch.deleteRecursively()
    }

    private fun server(): RemoteServer {
        val port = ServerSocket(0).use { it.localPort }
        controller.start(
            FtpServerConfig(
                port = port,
                username = "tester",
                password = "s3cret",
                anonymous = false,
                readOnly = false,
                rootPath = root.absolutePath,
            ),
        )
        return RemoteServer(
            type = RemoteType.FTP,
            host = "127.0.0.1",
            port = port,
            username = "tester",
            password = "s3cret",
        )
    }

    @Test
    fun `browsing while a download runs spoils neither`() {
        val content = Random(7).nextBytes(48 * 1024 * 1024)
        File(root, "big.bin").writeBytes(content)
        File(root, "notes.txt").writeText("hello")

        val server = server()
        val repository = RemoteRepository(RemoteConnections(), scratch)
        val big = runBlocking { repository.list(server, "/") }.first { it.name == "big.bin" }

        val target = File(scratch, "big.bin")
        val downloadFailure = AtomicReference<Throwable?>(null)
        val download = thread {
            runCatching { runBlocking { repository.download(server, big, target) } }
                .onFailure { downloadFailure.set(it) }
        }

        // What the user does meanwhile: open folders.
        var listings = 0
        var listFailure: Throwable? = null
        while (download.isAlive && listFailure == null) {
            runCatching { runBlocking { repository.list(server, "/") } }
                .onSuccess { entries ->
                    assertEquals(setOf("big.bin", "notes.txt"), entries.map { it.name }.toSet())
                    listings++
                }
                .onFailure { listFailure = it }
        }
        download.join()

        assertNull("the download failed", downloadFailure.get())
        assertNull("a listing failed", listFailure)
        assertTrue("never listed while the download ran", listings > 0)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `a server edited mid-transfer does not hand the old connection back`() {
        File(root, "notes.txt").writeText("hello")
        val server = server()
        val opened = mutableListOf<RemoteClient>()
        val connections = RemoteConnections { s -> FtpRemoteClient(s).also { opened += it } }

        connections.use(server) { client ->
            // Saved with new settings while this one is still in use.
            connections.disconnect(server.id)
            client.list("/")
        }
        connections.use(server) { it.list("/") }

        assertEquals("the stale connection was reused", 2, opened.size)
    }

    @Test
    fun `a finished operation leaves its connection for the next one`() {
        File(root, "notes.txt").writeText("hello")
        val server = server()
        var opened = 0
        val connections = RemoteConnections { s -> FtpRemoteClient(s).also { opened++ } }

        repeat(3) { connections.use(server) { it.list("/") } }

        assertEquals(1, opened)
    }
}
