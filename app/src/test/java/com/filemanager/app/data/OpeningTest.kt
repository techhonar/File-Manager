package com.filemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningTest {

    @Test
    fun `an archive asks about extracting it, whatever kind it is`() {
        for (name in listOf("photos.zip", "PHOTOS.ZIP", "backup.tar.gz", "data.7z", "album.rar", "logs.tgz", "dump.xz")) {
            assertEquals(name, Opening.EXTRACT, openingFor(name))
        }
    }

    @Test
    fun `a bundle is installed, though it is a zip inside`() {
        for (name in listOf("game.xapk", "app.apks", "app.apkm")) {
            assertEquals(name, Opening.INSTALL, openingFor(name))
        }
    }

    @Test
    fun `documents open in the app's own viewer`() {
        for (name in listOf("notes.txt", "manual.pdf", "letter.docx", "config.json")) {
            assertEquals(name, Opening.VIEW, openingFor(name))
        }
    }

    @Test
    fun `anything else goes to another app`() {
        for (name in listOf("app.apk", "song.mp3", "clip.mp4", "photo.jpg", "disk.iso", "README")) {
            assertEquals(name, Opening.HAND_ON, openingFor(name))
        }
    }
}
