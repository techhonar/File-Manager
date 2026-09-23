package com.filemanager.app.data.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BundlePlanTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A bundle on disk with these entries and contents. */
    private fun bundle(vararg entries: Pair<String, String>): File {
        val file = File(temp.root, "game.xapk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a split app bundle installs every APK at its top`() {
        // An .xapk as APKPure writes one for an App Bundle.
        val plan = planBundle(
            listOf(
                "manifest.json",
                "icon.png",
                "com.example.app.apk",
                "config.arm64_v8a.apk",
                "config.xxhdpi.apk",
                "config.en.apk",
            ),
        )!!
        assertEquals(
            listOf("com.example.app.apk", "config.arm64_v8a.apk", "config.en.apk", "config.xxhdpi.apk"),
            plan.apks,
        )
        assertTrue(plan.gameData.isEmpty())
    }

    @Test
    fun `a game's data is found beside its APK`() {
        val plan = planBundle(
            listOf(
                "com.example.game.apk",
                "Android/obb/com.example.game/main.12.com.example.game.obb",
                "Android/obb/com.example.game/patch.12.com.example.game.obb",
                "icon.png",
            ),
        )!!
        assertEquals(listOf("com.example.game.apk"), plan.apks)
        assertEquals(
            listOf(
                GameData(
                    "Android/obb/com.example.game/main.12.com.example.game.obb",
                    "com.example.game",
                    "main.12.com.example.game.obb",
                ),
                GameData(
                    "Android/obb/com.example.game/patch.12.com.example.game.obb",
                    "com.example.game",
                    "patch.12.com.example.game.obb",
                ),
            ),
            plan.gameData,
        )
    }

    @Test
    fun `APKs below the top are not installed`() {
        // bundletool's own .apks: standalone APKs for old phones sit in a
        // folder of their own, and installing one with the splits fails.
        assertNull(
            planBundle(listOf("toc.pb", "splits/base-master.apk", "standalones/standalone-arm64.apk")),
        )
        assertEquals(listOf("base.apk"), planBundle(listOf("base.apk", "extra/other.apk"))!!.apks)
    }

    @Test
    fun `a zip with no app in it has nothing to install`() {
        assertNull(planBundle(listOf("readme.txt", "photo.jpg")))
        assertNull(planBundle(emptyList()))
    }

    @Test
    fun `no entry's name can place game data outside its folder`() {
        val plan = planBundle(
            listOf(
                "base.apk",
                "Android/obb/../../evil.obb",
                "Android/obb/com.example.game/../escape.obb",
                "Android/obb/com.example.game/sub/nested.obb",
                "Android/obb/../evil/x.obb",
                "/Android/obb/com.example.game/rooted.obb",
                "Android\\obb\\com.example.game\\windows.obb",
                "Android/obb/nodots/x.obb",
                "Android/obb/com.example.game/not-game-data.txt",
            ),
        )!!
        assertTrue("took ${plan.gameData}", plan.gameData.isEmpty())
    }

    @Test
    fun `names are matched without regard to case`() {
        val plan = planBundle(listOf("BASE.APK", "android/OBB/com.example.game/Main.OBB"))!!
        assertEquals(listOf("BASE.APK"), plan.apks)
        assertEquals("com.example.game", plan.gameData.single().packageName)
        assertEquals("Main.OBB", plan.gameData.single().fileName)
    }

    @Test
    fun `only bundle names count as bundles`() {
        assertTrue(isBundle("game.XAPK"))
        assertTrue(isBundle("app.apks"))
        assertTrue(isBundle("app.apkm"))
        assertFalse("a single APK goes to the system installer", isBundle("app.apk"))
        assertFalse(isBundle("xapk"))
        assertFalse(isBundle("notes.txt"))
    }

    @Test
    fun `the installer's answer is put in words`() {
        assertEquals("Installed Maps", installOutcomeMessage(PackageInstaller.STATUS_SUCCESS, "Maps", null))
        assertNull(
            "cancelling in the system's dialog needs no reply",
            installOutcomeMessage(PackageInstaller.STATUS_FAILURE_ABORTED, "Maps", null),
        )
        assertEquals(
            "Not enough space to install Maps",
            installOutcomeMessage(PackageInstaller.STATUS_FAILURE_STORAGE, "Maps", "INSTALL_FAILED_INSUFFICIENT_STORAGE"),
        )
        assertEquals(
            "Could not install Maps: split missing",
            installOutcomeMessage(PackageInstaller.STATUS_FAILURE, "Maps", "split missing"),
        )
        assertEquals("Could not install Maps", installOutcomeMessage(PackageInstaller.STATUS_FAILURE, "Maps", " "))
    }

    @Test
    fun `game data is unpacked into a folder named for its app`() {
        val file = bundle(
            "com.example.game.apk" to "apk",
            "Android/obb/com.example.game/main.1.com.example.game.obb" to "main data",
            "Android/obb/com.example.game/patch.1.com.example.game.obb" to "patch data",
            "Android/obb/../../escape.obb" to "not this",
        )
        val into = temp.newFolder("Download")

        val folders = ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            unpackGameData(zip, planBundle(names)!!.gameData, into)
        }

        val folder = File(into, "com.example.game")
        assertEquals(listOf(folder), folders)
        assertEquals("main data", File(folder, "main.1.com.example.game.obb").readText())
        assertEquals("patch data", File(folder, "patch.1.com.example.game.obb").readText())
        assertEquals(
            "only the two data files, and nothing half-written",
            setOf("main.1.com.example.game.obb", "patch.1.com.example.game.obb"),
            folder.list()!!.toSet(),
        )
        assertFalse("an entry named to climb out was written", File(temp.root, "escape.obb").exists())
    }

    @Test
    fun `unpacking again replaces what an earlier attempt left`() {
        val file = bundle(
            "com.example.game.apk" to "apk",
            "Android/obb/com.example.game/main.1.com.example.game.obb" to "new data",
        )
        val into = temp.newFolder("Download")
        File(into, "com.example.game").mkdirs()
        File(into, "com.example.game/main.1.com.example.game.obb").writeText("old data")

        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            unpackGameData(zip, planBundle(names)!!.gameData, into)
        }

        assertEquals("new data", File(into, "com.example.game/main.1.com.example.game.obb").readText())
    }
}
