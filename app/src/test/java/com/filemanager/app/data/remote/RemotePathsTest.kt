package com.filemanager.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The path rules every protocol client depends on.
 *
 * Worth pinning down because all four share them and each one's server
 * punishes a mistake differently: an FTP server answers 550 for a doubled
 * slash, SMB wants the separators the other way round, and a path that can
 * walk out of its root with ".." is how a client writes somewhere it should
 * not be able to reach.
 */
class RemotePathsTest {

    @Test
    fun `normalise gives one leading slash and no trailing one`() {
        assertEquals("/", RemotePaths.normalise(""))
        assertEquals("/", RemotePaths.normalise("/"))
        assertEquals("/a/b", RemotePaths.normalise("/a/b/"))
        assertEquals("/a/b", RemotePaths.normalise("//a///b"))
        assertEquals("/a/b", RemotePaths.normalise("a/b"))
    }

    @Test
    fun `normalise resolves dot and dot-dot`() {
        assertEquals("/a/b", RemotePaths.normalise("/a/./b"))
        assertEquals("/a/c", RemotePaths.normalise("/a/b/../c"))
    }

    @Test
    fun `dot-dot cannot climb past the root`() {
        assertEquals("/etc", RemotePaths.normalise("/../../etc"))
        assertEquals("/", RemotePaths.normalise("/.."))
    }

    @Test
    fun `join never doubles the separator`() {
        assertEquals("/a/b", RemotePaths.join("/a", "b"))
        assertEquals("/b", RemotePaths.join("/", "b"))
        assertEquals("/a/b", RemotePaths.join("/a/", "b"))
    }

    @Test
    fun `parent stops at the root`() {
        assertEquals("/a/b", RemotePaths.parent("/a/b/c"))
        assertEquals("/", RemotePaths.parent("/a"))
        assertEquals("/", RemotePaths.parent("/"))
    }

    @Test
    fun `name is the last segment`() {
        assertEquals("c.txt", RemotePaths.name("/a/b/c.txt"))
        assertEquals("a", RemotePaths.name("/a"))
        assertEquals("", RemotePaths.name("/"))
        assertEquals("v1.2.3.tar.gz", RemotePaths.name("/a/v1.2.3.tar.gz"))
    }

    @Test
    fun `smb paths are backslashed and relative to the share`() {
        assertEquals("a\\b", RemotePaths.toSmb("/a/b"))
        assertEquals("", RemotePaths.toSmb("/"))
        assertEquals("a\\b", RemotePaths.toSmb("//a//b/"))
    }

    @Test
    fun `names keep their spaces and dots`() {
        assertEquals("/my folder/a b.txt", RemotePaths.normalise("/my folder/a b.txt"))
        assertEquals("/.hidden", RemotePaths.normalise("/.hidden"))
    }
}
