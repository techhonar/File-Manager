package com.filemanager.app.data.transfer

import com.filemanager.app.data.ftpd.TestFtpServer
import com.filemanager.app.data.remote.RemoteConnections
import com.filemanager.app.data.remote.RemoteEntry
import com.filemanager.app.data.remote.RemoteRepository
import com.filemanager.app.data.remote.RemoteServer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Downloads and uploads as the app runs them, against the app's own kind of
 * FTP server - everything but the notification, which is the host's.
 */
class TransferCenterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private val servers = mutableListOf<TestFtpServer>()
    private val trashed = CopyOnWriteArrayList<String>()
    private val finished = CopyOnWriteArrayList<Transfer>()
    private val starts = AtomicInteger()

    private val host = object : TransferCenter.Host {
        override fun started() {
            starts.incrementAndGet()
        }

        override fun finished(transfer: Transfer) {
            finished += transfer
        }
    }

    private val repository by lazy { RemoteRepository(RemoteConnections(), temp.newFolder("cache")) }

    private val center by lazy {
        TransferCenter(
            repository = repository,
            trash = { paths -> trashed += paths; paths.size },
            host = host,
            progressIntervalMs = 0,
        )
    }

    @Before
    fun setUp() {
        root = temp.newFolder("served")
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun server(bytesPerSecond: Int = 0, writable: Boolean = true): RemoteServer =
        TestFtpServer(root, bytesPerSecond, writable).also { servers += it }.remote

    private fun listing(server: RemoteServer): List<RemoteEntry> =
        runBlocking { repository.list(server, "/") }.filterNot { it.isDir }

    private fun outcomeOf(transfer: StateFlow<Transfer>): Transfer =
        runBlocking { withTimeout(60_000) { transfer.first { it.outcome != null } } }

    /** Wait until [transfer] has moved some bytes but not all of them. */
    private fun partway(transfer: StateFlow<Transfer>) {
        runBlocking {
            withTimeout(30_000) { transfer.first { it.bytesDone > 0 && it.outcome == null } }
        }
    }

    @Test
    fun `a batch comes down whole, and says so`() {
        val photo = Random(1).nextBytes(300_000)
        File(root, "photo.jpg").writeBytes(photo)
        File(root, "notes.txt").writeText("hello")
        val server = server()
        val into = temp.newFolder("Download")

        val transfer = center.download(server, listing(server), into)
        val done = outcomeOf(transfer)

        assertArrayEquals(photo, File(into, "photo.jpg").readBytes())
        assertEquals("hello", File(into, "notes.txt").readText())
        val outcome = done.outcome!!
        assertEquals(2, outcome.succeeded)
        assertEquals(0, outcome.failed)
        assertFalse(outcome.cancelled)
        assertEquals("Downloaded 2 to Download", outcome.summary)
        assertEquals("every byte is counted", done.bytesTotal, done.bytesDone)
        assertEquals(300_005L, done.bytesTotal)
        assertEquals(2, done.filesDone)

        assertTrue("the host was told it began", starts.get() > 0)
        assertEquals("and how it ended", listOf(done.id), finished.map { it.id })
        assertTrue("nothing is left running", center.active.value.isEmpty())
        assertEquals("nor can be followed", null, center.follow(done.id))
    }

    @Test
    fun `a file already there is not written over`() {
        File(root, "notes.txt").writeText("from the server")
        val server = server()
        val into = temp.newFolder("Download")
        File(into, "notes.txt").writeText("mine")

        outcomeOf(center.download(server, listing(server), into))

        assertEquals("mine", File(into, "notes.txt").readText())
        assertEquals("from the server", File(into, "notes (1).txt").readText())
    }

    @Test
    fun `stopping a download leaves nothing half written`() {
        File(root, "video.mp4").writeBytes(Random(2).nextBytes(512 * 1024))
        val server = server(bytesPerSecond = 64 * 1024)
        val into = temp.newFolder("Download")

        val transfer = center.download(server, listing(server), into)
        partway(transfer)
        center.cancel(transfer.value.id)
        val done = outcomeOf(transfer)

        val outcome = done.outcome!!
        assertTrue(outcome.cancelled)
        assertEquals(0, outcome.succeeded)
        assertEquals("a stop is not a failure", 0, outcome.failed)
        assertEquals("Download stopped: 0 of 1 downloaded", outcome.summary)
        assertEquals("the partial file was left", emptyList<String>(), into.list()!!.toList())
    }

    @Test
    fun `an upload takes a free name and a move trashes only what arrived`() {
        File(root, "notes.txt").writeText("already on the server")
        val server = server()
        val local = temp.newFolder("Phone")
        val notes = File(local, "notes.txt").apply { writeText("from the phone") }
        val photo = File(local, "photo.jpg").apply { writeBytes(Random(3).nextBytes(100_000)) }

        val done = outcomeOf(
            center.upload(
                server = server,
                files = listOf(notes, photo),
                folder = "/",
                taken = setOf("notes.txt"),
                move = true,
            ),
        )

        assertEquals("already on the server", File(root, "notes.txt").readText())
        assertEquals("from the phone", File(root, "notes (1).txt").readText())
        assertArrayEquals(photo.readBytes(), File(root, "photo.jpg").readBytes())
        assertEquals(setOf(notes.path, photo.path), trashed.toSet())
        assertEquals("Moved 2", done.outcome!!.summary)
    }

    @Test
    fun `an upload that fails keeps the originals`() {
        val server = server(writable = false)
        val local = temp.newFolder("Phone")
        val notes = File(local, "notes.txt").apply { writeText("from the phone") }

        val done = outcomeOf(
            center.upload(server, listOf(notes), "/", emptySet(), move = true, skipped = 1),
        )

        val outcome = done.outcome!!
        assertEquals(0, outcome.succeeded)
        assertEquals(1, outcome.failed)
        assertEquals("Moved 0, 1 failed, 1 folders skipped", outcome.summary)
        assertTrue("an original was trashed for an upload that failed", trashed.isEmpty())
    }

    @Test
    fun `stopping an upload removes what arrived of it`() {
        val server = server(bytesPerSecond = 64 * 1024)
        val local = temp.newFolder("Phone")
        val video = File(local, "video.mp4").apply { writeBytes(Random(4).nextBytes(512 * 1024)) }

        val transfer = center.upload(server, listOf(video), "/", emptySet(), move = false)
        partway(transfer)
        center.cancel(transfer.value.id)
        val done = outcomeOf(transfer)

        assertTrue(done.outcome!!.cancelled)
        assertEquals("Upload stopped: 0 of 1 uploaded", done.outcome!!.summary)
        assertFalse("half a file was left on the server", File(root, "video.mp4").exists())
        assertTrue("the original stays", video.exists())
    }

    @Test
    fun `a running transfer can be followed and is listed until it ends`() {
        File(root, "video.mp4").writeBytes(Random(5).nextBytes(256 * 1024))
        val server = server(bytesPerSecond = 64 * 1024)
        val into = temp.newFolder("Download")

        val transfer = center.download(server, listing(server), into)
        partway(transfer)
        val id = transfer.value.id

        assertEquals(listOf(id), center.active.value.map { it.id })
        val followed = center.follow(id)
        assertNotNull(followed)
        assertEquals("video.mp4", center.active.value.single().current)

        outcomeOf(transfer)
        assertTrue(center.active.value.isEmpty())
    }

    // --- Copying and moving on the phone, through a stand-in for the core ---

    private val movedTold = CopyOnWriteArrayList<List<Pair<String, String>>>()

    private fun centerWith(local: LocalFiles) = TransferCenter(
        repository = repository,
        trash = { paths -> trashed += paths; paths.size },
        host = host,
        local = local,
        moved = { movedTold += it },
        progressIntervalMs = 0,
    )

    @Test
    fun `a copy on the phone counts files and bytes, and says where each went`() {
        val center = centerWith { sources, into, _, progress, placed, _ ->
            progress(0, 2, 0, 100)
            placed(sources[0], "$into/a.jpg")
            progress(1, 2, 60, 100)
            placed(sources[1], "$into/b (1).jpg")
            progress(2, 2, 100, 100)
        }

        val done = outcomeOf(center.place(listOf("/p/a.jpg", "/p/b.jpg"), "/q", move = false))

        assertEquals(Direction.COPY, done.direction)
        assertEquals("the files the core counted, not the items", 2, done.fileCount)
        assertEquals(2, done.filesDone)
        assertEquals(100L, done.bytesDone)
        val outcome = done.outcome!!
        assertEquals("Copy completed.", outcome.summary)
        assertEquals(listOf("/p/a.jpg" to "/q/a.jpg", "/p/b.jpg" to "/q/b (1).jpg"), outcome.placed)
        assertTrue("a copy moves nothing to follow", movedTold.isEmpty())
        assertEquals(listOf(done.id), finished.map { it.id })
        assertTrue("over, so no longer running", center.active.value.isEmpty())
    }

    @Test
    fun `a move says where things went, so what is remembered of them follows`() {
        val center = centerWith { sources, into, move, _, placed, _ ->
            assertTrue(move)
            placed(sources[0], "$into/clip.mp4")
            // Already there: not a move at all.
            placed(sources[1], sources[1])
        }

        val done = outcomeOf(center.place(listOf("/p/clip.mp4", "/q/stays.txt"), "/q", move = true))

        assertEquals("Move completed.", done.outcome!!.summary)
        assertEquals(listOf(listOf("/p/clip.mp4" to "/q/clip.mp4")), movedTold)
    }

    @Test
    fun `cancelling stops the core between buffers, and says nothing was copied`() {
        val stopAsked = java.util.concurrent.CountDownLatch(1)
        val center = centerWith { _, _, _, progress, _, stop ->
            stop.onStop { stopAsked.countDown() }
            progress(0, 1, 10, 1000)
            // The core checks its token between buffers; here, it waits for it.
            assertTrue("never stopped", stopAsked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            throw java.io.IOException("Cancelled")
        }

        val running = center.place(listOf("/p/movie.mkv"), "/q", move = false)
        partway(running)
        center.cancel(running.value.id)
        val done = outcomeOf(running)

        val outcome = done.outcome!!
        assertTrue(outcome.cancelled)
        assertEquals(0, outcome.failed)
        assertEquals("File transfer cancelled. File not copied.", outcome.summary)
    }

    @Test
    fun `a copy that fails partway says why, and keeps what arrived`() {
        val center = centerWith { sources, into, _, _, placed, _ ->
            placed(sources[0], "$into/one.txt")
            throw java.io.IOException("No space left on device")
        }

        val done = outcomeOf(center.place(listOf("/p/one.txt", "/p/two.txt"), "/q", move = false))

        val outcome = done.outcome!!
        assertFalse(outcome.cancelled)
        assertEquals(1, outcome.succeeded)
        assertEquals(1, outcome.failed)
        assertEquals("1 of 2 copied. Could not copy the rest: No space left on device", outcome.summary)
    }
}
