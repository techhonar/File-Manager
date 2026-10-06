package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.filemanager_core.ArchiveEntry

class ArchiveTreeTest {

    private fun file(name: String, size: Long) = ArchiveEntry(name, size.toULong(), 0uL, false, 0uL)

    private fun folder(name: String) = ArchiveEntry(name, 0uL, 0uL, true, 0uL)

    /** Each row as "  name/" by depth, for comparing whole trees at a glance. */
    private fun shape(rows: List<ArchiveRow>) =
        rows.map { "${"  ".repeat(it.depth)}${it.name}${if (it.isDir) "/" else ""}" }

    @Test
    fun `files sit under the folders their paths name, folders first`() {
        // As most zips are made: no folder entries at all, only file paths.
        val tree = archiveTree(
            listOf(
                file("readme.txt", 10),
                file("photos/2024/beach.jpg", 300),
                file("photos/cover.jpg", 100),
                file("Album/song.mp3", 50),
            ),
        )
        assertEquals(
            listOf("Album/", "  song.mp3", "photos/", "  2024/", "    beach.jpg", "  cover.jpg", "readme.txt"),
            shape(tree),
        )
        val photos = tree.first { it.path == "photos" }
        assertEquals("everything inside, at any depth", 400L, photos.size)
        assertEquals(2, photos.files)
        assertEquals("photos/2024/beach.jpg", tree.first { it.name == "beach.jpg" }.path)
    }

    @Test
    fun `a folder named on its own is kept, even empty`() {
        val tree = archiveTree(listOf(folder("empty"), folder("docs"), file("docs/a.txt", 1)))
        assertEquals(listOf("docs/", "  a.txt", "empty/"), shape(tree))
        assertEquals(0, tree.first { it.path == "empty" }.files)
    }

    @Test
    fun `only what is inside open folders shows`() {
        val tree = archiveTree(
            listOf(file("a/b/deep.txt", 1), file("a/top.txt", 1), file("other.txt", 1)),
        )
        assertEquals(listOf("a/", "other.txt"), shape(visibleRows(tree, emptySet())))
        assertEquals(listOf("a/", "  b/", "  top.txt", "other.txt"), shape(visibleRows(tree, setOf("a"))))
        assertEquals(
            "a folder inside a closed one stays hidden, open or not",
            listOf("a/", "other.txt"),
            shape(visibleRows(tree, setOf("a/b"))),
        )
        assertEquals(
            listOf("a/", "  b/", "    deep.txt", "  top.txt", "other.txt"),
            shape(visibleRows(tree, setOf("a", "a/b"))),
        )
    }

    @Test
    fun `an archive holding one folder opens on what is inside it`() {
        val single = archiveTree(listOf(file("project/src/main.kt", 1), file("project/README", 1)))
        assertEquals(setOf("project"), initiallyOpen(single))

        val several = archiveTree(listOf(file("a.txt", 1), file("b.txt", 1)))
        assertTrue(initiallyOpen(several).isEmpty())
        assertTrue("a lone file is not a folder to open", initiallyOpen(archiveTree(listOf(file("a.txt", 1)))).isEmpty())
    }

    @Test
    fun `the summary counts files and folders and adds up their size`() {
        val size = { bytes: Long -> "${bytes}B" }
        val tree = archiveTree(listOf(file("photos/a.jpg", 30), file("photos/b.jpg", 20), file("notes.txt", 5)))
        assertEquals("3 files in 1 folder · 55B", archiveSummary(tree, size))
        assertEquals("1 file", archiveSummary(archiveTree(listOf(file("empty.txt", 0))), size))
    }

    @Test
    fun `names that are only slashes are skipped`() {
        assertEquals(listOf("a.txt"), shape(archiveTree(listOf(file("/", 0), file("a.txt", 1)))))
    }
}
