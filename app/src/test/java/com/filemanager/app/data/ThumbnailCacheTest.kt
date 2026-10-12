package com.filemanager.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThumbnailCacheTest {

    private val root = Files.createTempDirectory("thumbs").toFile()
    private val dir = File(root, "cache")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun source(name: String) = File(root, name).apply { writeBytes(ByteArray(10)) }

    private fun cache(maxBytes: Long = 1_000_000, maxEntries: Int = 1_000) =
        ThumbnailCache(dir, ThumbnailCache.Format.JPEG, maxBytes, maxEntries)

    @Test
    fun `a file found to have no picture is remembered as such`() {
        val track = source("song.mp3")
        val cache = cache()
        assertEquals(ThumbnailCache.Lookup.Unknown, cache.lookup(track))

        cache.putNoPicture(track)
        assertEquals(ThumbnailCache.Lookup.NoPicture, cache.lookup(track))
        assertEquals("nothing else is touched", ThumbnailCache.Lookup.Unknown, cache.lookup(source("other.mp3")))
    }

    @Test
    fun `a file changed since is looked at again`() {
        val grown = source("grown.mp3")
        val touched = source("touched.mp3")
        val cache = cache()
        cache.putNoPicture(grown)
        cache.putNoPicture(touched)

        grown.appendBytes(ByteArray(5))
        touched.setLastModified(touched.lastModified() + 5_000)

        assertEquals(ThumbnailCache.Lookup.Unknown, cache.lookup(grown))
        assertEquals(ThumbnailCache.Lookup.Unknown, cache.lookup(touched))
    }

    /** Ten entries of [bytes] each, a second apart, oldest first. */
    private fun entries(bytes: Int): List<File> {
        dir.mkdirs()
        return (0 until 10).map { i ->
            File(dir, "entry$i.jpg").apply {
                writeBytes(ByteArray(bytes))
                setLastModified(1_000_000_000L + i * 1_000L)
            }
        }
    }

    private fun left() = dir.listFiles()!!.map { it.name }.sorted()

    @Test
    fun `trimming drops the oldest until the size fits`() {
        val entries = entries(bytes = 100)
        cache(maxBytes = 650).trim()
        assertEquals(entries.drop(4).map { it.name }, left())
    }

    @Test
    fun `trimming counts entries too, as an empty one takes no room`() {
        val entries = entries(bytes = 0)
        cache(maxEntries = 3).trim()
        assertEquals(entries.drop(7).map { it.name }, left())
    }

    @Test
    fun `trimming clears writes that never finished, and keeps a cache within bounds as it is`() {
        val entries = entries(bytes = 100)
        val partial = File(dir, "entry.jpg123.part").apply { writeBytes(ByteArray(50)) }
        cache().trim()
        assertFalse(partial.exists())
        assertEquals(entries.map { it.name }, left())
    }

    @Test
    fun `a picture is shrunk in steps the decoder can take, never below the size wanted`() {
        assertEquals("a 3000-pixel cover", 8, thumbnailSampleSize(3000, 3000, 256))
        assertEquals(2, thumbnailSampleSize(1024, 768, 256))
        assertEquals("exactly the size wanted", 1, thumbnailSampleSize(256, 256, 256))
        assertEquals("smaller already", 1, thumbnailSampleSize(100, 100, 256))
        assertEquals("the short side decides", 1, thumbnailSampleSize(4000, 300, 256))
    }
}
