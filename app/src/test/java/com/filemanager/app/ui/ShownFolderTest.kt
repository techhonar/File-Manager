package com.filemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownFolderTest {

    @Test
    fun `the folder on screen is where the browser is now, not where it began`() {
        val shown = ShownFolder()
        val screen = Any()
        assertNull(shown.path.value)
        shown.show(screen, "/storage/emulated/0")
        // Into a folder, on the same screen: it lets go of the one, then says the next.
        shown.leave(screen)
        shown.show(screen, "/storage/emulated/0/Download")
        assertEquals("/storage/emulated/0/Download", shown.path.value)
        shown.leave(screen)
        assertNull(shown.path.value)
    }

    @Test
    fun `a screen going after another has come up takes nothing back`() {
        val shown = ShownFolder()
        val under = Any()
        val over = Any()
        shown.show(under, "/storage/emulated/0")
        // The new screen is up before the one under it has gone.
        shown.show(over, "/storage/emulated/0/Pictures")
        shown.leave(under)
        assertEquals("/storage/emulated/0/Pictures", shown.path.value)
        // And back: the one under comes up again before the one over it goes.
        shown.show(under, "/storage/emulated/0")
        shown.leave(over)
        assertEquals("/storage/emulated/0", shown.path.value)
    }
}
