package com.filemanager.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.filemanager_core.FileCategory
import uniffi.filemanager_core.FileEntry

class SelectionMoreTest {

    private fun file(name: String) =
        FileEntry(name, "/storage/emulated/0/Download/$name", 10uL, false, false, 0uL, FileCategory.DOCUMENT)

    private fun folder(name: String) =
        FileEntry(name, "/storage/emulated/0/$name", 0uL, true, false, 0uL, FileCategory.DIRECTORY)

    private val calls = mutableListOf<String>()

    private fun menu(
        vararg selected: FileEntry,
        allFavorite: Boolean = false,
        showInFolder: Boolean = true,
    ) = selectionMoreActions(
        selected = selected.toList(),
        allFavorite = allFavorite,
        onCopyToClipboard = { calls += "clipboard" },
        onDetails = { calls += "details ${it.name}" },
        onRename = { calls += "rename ${it.name}" },
        onFavorite = { calls += "favourite" },
        onOpenWith = { calls += "open with ${it.name}" },
        onShowInFolder = if (showInFolder) ({ calls += "show ${it.name}" }) else null,
    )

    /** "label" per item, with "|" where a line separates two groups. */
    private fun shape(actions: List<MoreAction>) = actions.flatMapIndexed { i, action ->
        if (i > 0 && action.group != actions[i - 1].group) listOf("|", action.label) else listOf(action.label)
    }

    @Test
    fun `one file gets the whole menu, in One UI's order`() {
        assertEquals(
            listOf("Copy to clipboard", "Details", "Rename", "Add to favourites", "|", "Open with", "Show in folder"),
            shape(menu(file("report.pdf"))),
        )
    }

    @Test
    fun `a folder's own list leaves Show in folder out`() {
        assertEquals(
            listOf("Copy to clipboard", "Details", "Rename", "Add to favourites", "|", "Open with"),
            shape(menu(file("report.pdf"), showInFolder = false)),
        )
    }

    @Test
    fun `several files get only what works on several`() {
        assertEquals(
            listOf("Copy to clipboard", "Add to favourites"),
            shape(menu(file("a.pdf"), file("b.pdf"))),
        )
    }

    @Test
    fun `a folder cannot go on the clipboard or be opened with an app`() {
        assertEquals(
            listOf("Details", "Rename", "Add to favourites", "|", "Show in folder"),
            shape(menu(folder("Photos"))),
        )
        assertEquals(listOf("Add to favourites"), shape(menu(file("a.pdf"), folder("Photos"))))
    }

    @Test
    fun `favourites already, it offers to remove them`() {
        assertTrue(shape(menu(file("a.pdf"), allFavorite = true)).contains("Remove from favourites"))
    }

    @Test
    fun `each item does what it says, to the file chosen`() {
        menu(file("report.pdf")).forEach { it.onClick() }
        assertEquals(
            listOf(
                "clipboard", "details report.pdf", "rename report.pdf", "favourite",
                "open with report.pdf", "show report.pdf",
            ),
            calls,
        )
    }

    @Test
    fun `where favouriting is on the bar, More leaves it out`() {
        val actions = selectionMoreActions(
            selected = listOf(file("report.pdf")),
            allFavorite = true,
            onCopyToClipboard = {},
            onDetails = {},
            onRename = {},
            onFavorite = null,
            onOpenWith = {},
            onShowInFolder = {},
        )
        assertEquals(
            listOf("Copy to clipboard", "Details", "Rename", "|", "Open with", "Show in folder"),
            shape(actions),
        )
    }

    @Test
    fun `nothing chosen, nothing offered`() {
        assertTrue(menu().isEmpty())
    }

    @Test
    fun `the title counts what is chosen, or says what to do`() {
        assertEquals("Select items", selectionTitle(0))
        assertEquals("1 selected", selectionTitle(1))
        assertEquals("12 selected", selectionTitle(12))
    }
}
