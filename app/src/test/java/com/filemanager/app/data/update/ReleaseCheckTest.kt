package com.filemanager.app.data.update

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ReleaseCheckTest {

    private class Memory : UpdateMemory {
        override var etag: String? = null
        override var latest: String? = null
        override var latestChanges: List<String> = emptyList()
        override var announced: String? = null
    }

    private val server = MockWebServer()
    private val memory = Memory()
    private lateinit var check: ReleaseCheck

    @Before
    fun start() {
        server.start()
        check = ReleaseCheck(OkHttpClient(), memory, server.url("/releases/latest").toString())
    }

    @After
    fun stop() = server.shutdown()

    /** As much of GitHub's answer as the check reads. */
    private fun release(tag: String, vararg assets: String, etag: String, notes: String? = null) = MockResponse()
        .setBody(
            JSONObject()
                .put("tag_name", tag)
                .put("assets", JSONArray(assets.map { JSONObject().put("name", it) }))
                .put("body", notes ?: JSONObject.NULL)
                .toString(),
        )
        .setHeader("ETag", etag)

    /** Notes as tools/release_notes.py writes them. */
    private val notes = """
        ## What changed

        4 changes - 1 new feature, 2 fixes, 1 internal change.

        ### New features

        **Lay out Favourites as One UI does, in an order the user keeps**

        Favourites are listed in the order they were added, which the user can
        change: holding one chooses it, as elsewhere.

        ### Fixes

        **Offer Details for several files at once**

        More offered Details for one file only.

        **Keep selecting until Cancel, with nothing ticked**

        ### Behind the scenes

        **Run the unit tests against the release build**

        **Full changelog:** https://github.com/techhonar/File-Manager/compare/v0.6.3...v0.6.4

        ---
    """.trimIndent()

    @Test
    fun `the latest release's version, its ETag kept for next time`() {
        server.enqueue(release("v0.6.0", "FileManager-0.6.0.apk", etag = "\"abc\""))

        assertEquals("0.6.0", check.latestVersion())
        assertNull("nothing to send the first time", server.takeRequest().getHeader("If-None-Match"))
        assertEquals("\"abc\"", memory.etag)
        assertEquals("0.6.0", memory.latest)
    }

    @Test
    fun `nothing new is a 304, answered from what was kept`() {
        server.enqueue(release("v0.6.0", "app.apk", etag = "\"abc\""))
        server.enqueue(MockResponse().setResponseCode(304))

        check.latestVersion()
        assertEquals("0.6.0", check.latestVersion())
        server.takeRequest()
        assertEquals("the second asks whether it changed", "\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `the changes its notes list are kept, for the 304s that follow`() {
        server.enqueue(release("v0.6.4", "app.apk", etag = "\"abc\"", notes = notes))
        server.enqueue(MockResponse().setResponseCode(304))

        assertEquals("0.6.4", check.latestVersion())
        assertEquals("0.6.4", check.latestVersion())
        assertEquals(
            listOf(
                "Lay out Favourites as One UI does, in an order the user keeps",
                "Offer Details for several files at once",
                "Keep selecting until Cancel, with nothing ticked",
            ),
            memory.latestChanges,
        )
    }

    @Test
    fun `a release without notes, or still without its APK, has no changes to tell`() {
        server.enqueue(release("v0.6.4", "app.apk", etag = "\"a\""))
        server.enqueue(release("v0.7.0", "notes.txt", etag = "\"b\"", notes = notes))

        check.latestVersion()
        assertEquals(emptyList<String>(), memory.latestChanges)
        check.latestVersion()
        assertEquals(emptyList<String>(), memory.latestChanges)
    }

    @Test
    fun `changes are the lines in bold, but not those behind the scenes or the changelog link`() {
        assertEquals(
            listOf(
                "Lay out Favourites as One UI does, in an order the user keeps",
                "Offer Details for several files at once",
                "Keep selecting until Cancel, with nothing ticked",
            ),
            releaseChanges(notes),
        )
        assertEquals("notes written some other way", emptyList<String>(), releaseChanges("Bug fixes and improvements."))
        assertEquals(emptyList<String>(), releaseChanges(""))
    }

    @Test
    fun `a release still waiting for its APK is not an update yet`() {
        server.enqueue(release("v0.7.0", "notes.txt", etag = "\"x\""))
        server.enqueue(release("v0.7.0", "notes.txt", "app.apk", etag = "\"y\""))

        assertNull(check.latestVersion())
        assertEquals("once the APK is there, it is", "0.7.0", check.latestVersion())
        server.takeRequest()
        assertEquals("\"x\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `out of requests for the hour, or an answer that makes no sense, says nothing`() {
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody("not json").setHeader("ETag", "\"z\""))

        assertNull(check.latestVersion())
        assertNull("nothing kept from a refusal", memory.etag)
        assertNull(check.latestVersion())
    }

    @Test
    fun `offline says nothing`() {
        val offline = ReleaseCheck(OkHttpClient(), memory, "http://127.0.0.1:1/releases/latest")
        assertNull(offline.latestVersion())
    }

    @Test
    fun `each version is announced once, and only when it is newer`() {
        assertEquals("0.6.0", versionToAnnounce("0.6.0", installed = "0.5.2", announced = null))
        assertNull("told already", versionToAnnounce("0.6.0", installed = "0.5.2", announced = "0.6.0"))
        assertEquals("a later one is told", "0.7.0", versionToAnnounce("0.7.0", installed = "0.5.2", announced = "0.6.0"))
        assertNull("up to date", versionToAnnounce("0.5.2", installed = "0.5.2", announced = null))
        assertNull("nothing known", versionToAnnounce(null, installed = "0.5.2", announced = null))
    }
}
