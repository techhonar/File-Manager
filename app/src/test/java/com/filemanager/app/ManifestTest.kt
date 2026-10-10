package com.filemanager.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the code asks of Android that only the manifest can allow, checked
 * here because the compiler cannot see it and the phone finds out too late.
 * 0.6.2 booked a job that waits for a network without ACCESS_NETWORK_STATE,
 * and on Android 14 and up the app closed the moment it opened.
 */
class ManifestTest {

    /** The app module: Gradle runs tests from inside it, the local runner from above. */
    private val module = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }

    private val manifest = File(module, "src/main/AndroidManifest.xml").readText()

    private val code = File(module, "src/main/java").walk()
        .filter { it.extension == "kt" }
        .joinToString("\n") { it.readText() }

    private val permissions = Regex("""<uses-permission\s+android:name="([^"]+)"""")
        .findAll(manifest).map { it.groupValues[1] }.toSet()

    @Test
    fun `a job that waits for a network is allowed to`() {
        if ("setRequiredNetworkType(" in code || "setRequiredNetwork(" in code) {
            assertTrue(
                "booking it throws on Android 14 and up without ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_NETWORK_STATE" in permissions,
            )
        }
    }

    @Test
    fun `a job kept across a restart is allowed to be`() {
        if ("setPersisted(true)" in code) {
            assertTrue(
                "booking it throws without RECEIVE_BOOT_COMPLETED",
                "android.permission.RECEIVE_BOOT_COMPLETED" in permissions,
            )
        }
    }

    @Test
    fun `every job service is one only the scheduler can start`() {
        val jobServices = Regex("""class (\w+)\s*:\s*JobService\(\)""").findAll(code).map { it.groupValues[1] }.toList()
        for (name in jobServices) {
            val declared = Regex("""<service\b[^>]*android:name="[^"]*\.$name"[^>]*>""").find(manifest)?.value
            assertTrue("$name is not in the manifest", declared != null)
            assertTrue(
                "$name needs BIND_JOB_SERVICE, or booking it throws",
                declared!!.contains("""android:permission="android.permission.BIND_JOB_SERVICE""""),
            )
            assertTrue("$name is not for other apps to start", declared.contains("""android:exported="false""""))
        }
    }
}
