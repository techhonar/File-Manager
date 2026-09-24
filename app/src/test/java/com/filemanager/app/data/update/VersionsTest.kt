package com.filemanager.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which release Update App offers, compared with what is installed. */
class VersionsTest {

    @Test
    fun `a later release is newer`() {
        assertTrue(isNewerVersion("0.4.6", "0.4.5"))
        assertTrue(isNewerVersion("v0.5.0", "0.4.9"))
        assertTrue(isNewerVersion("1.0", "0.9.9"))
    }

    @Test
    fun `parts are compared as numbers, not as text`() {
        assertTrue(isNewerVersion("0.4.10", "0.4.9"))
        assertFalse(isNewerVersion("0.4.9", "0.4.10"))
    }

    @Test
    fun `the same or an older release is not`() {
        assertFalse(isNewerVersion("v0.4.5", "0.4.5"))
        assertFalse(isNewerVersion("0.5", "0.5.0"))
        assertFalse(isNewerVersion("0.4.4", "0.4.5"))
    }

    @Test
    fun `a tag that is not a version is never offered`() {
        assertFalse(isNewerVersion("latest", "0.4.5"))
        assertFalse(isNewerVersion("0.4.6-beta", "0.4.5"))
        assertFalse(isNewerVersion("", "0.4.5"))
    }

    @Test
    fun `an installed build with an odd name takes any release`() {
        assertTrue(isNewerVersion("0.4.5", "dev"))
    }
}
