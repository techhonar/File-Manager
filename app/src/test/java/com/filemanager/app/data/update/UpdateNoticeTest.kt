package com.filemanager.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateNoticeTest {

    @Test
    fun `it names the version and how much it changes`() {
        val notice = updateNotice("0.6.4", listOf("Offer Details for several files at once", "Keep selecting until Cancel"))
        assertEquals("Version 0.6.4 is ready", notice.title)
        assertEquals("2 changes. Tap to update.", notice.text)
        assertEquals("• Offer Details for several files at once\n• Keep selecting until Cancel", notice.details)
    }

    @Test
    fun `one change, or none known`() {
        assertEquals("1 change. Tap to update.", updateNotice("0.6.4", listOf("A fix")).text)

        val unknown = updateNotice("0.6.4", emptyList())
        assertEquals("Tap to update.", unknown.text)
        assertNull("nothing to pull down to", unknown.details)
    }

    @Test
    fun `a long list stops at six, and says how many more`() {
        val notice = updateNotice("0.7.0", (1..9).map { "Change $it" })
        assertEquals("9 changes. Tap to update.", notice.text)
        assertEquals(
            (1..6).joinToString("\n") { "• Change $it" } + "\nand 3 more",
            notice.details,
        )
    }
}
