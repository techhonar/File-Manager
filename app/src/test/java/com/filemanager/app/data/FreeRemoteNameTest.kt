package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Uploads must not land on top of what is already on the server. */
class FreeRemoteNameTest {

    @Test
    fun `free names pass through`() {
        assertEquals("a.txt", freeRemoteName(setOf("b.txt"), "a", "txt"))
    }

    @Test
    fun `a taken name gets a number before the extension`() {
        assertEquals("a (1).txt", freeRemoteName(setOf("a.txt"), "a", "txt"))
        assertEquals("a (2).txt", freeRemoteName(setOf("a.txt", "a (1).txt"), "a", "txt"))
    }

    @Test
    fun `two files with one name in the same paste get different names`() {
        // The set grows as names are used; the upload loop relies on that.
        val taken = mutableSetOf<String>()
        val first = freeRemoteName(taken, "a", "txt").also { taken += it }
        val second = freeRemoteName(taken, "a", "txt").also { taken += it }
        assertEquals("a.txt", first)
        assertEquals("a (1).txt", second)
    }

    @Test
    fun `a name without an extension`() {
        assertEquals("notes (1)", freeRemoteName(setOf("notes"), "notes", ""))
    }
}
