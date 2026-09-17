package com.filemanager.app.data.remote

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * The WebDAV client against a server that answers the way a real one does.
 *
 * This client parses XML rather than calling a library, so the parsing is the
 * part worth pinning down: which response is the folder itself, how a href is
 * encoded, where the size and date live. The mock deliberately answers with a
 * different namespace prefix and percent-encoded hrefs, because real servers
 * do both and a client that only handles its own output would pass and then
 * fail against Nextcloud.
 */
class WebDavClientTest {

    private lateinit var root: File
    private lateinit var http: MockWebServer
    private var port = 0

    private val credential = "Basic " + Base64.getEncoder()
        .encodeToString("dav:secret".toByteArray())

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dav-test").toFile()
        http = MockWebServer()
        http.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        http.start()
        port = http.port
    }

    @After
    fun tearDown() {
        http.shutdown()
        root.deleteRecursively()
    }

    private fun client(password: String = "secret") = WebDavRemoteClient(
        RemoteServer(
            type = RemoteType.WEBDAV,
            host = "127.0.0.1",
            port = port,
            username = "dav",
            password = password,
        ),
    )

    @Test
    fun `lists a folder without including the folder itself`() {
        File(root, "photos").mkdirs()
        File(root, "notes.txt").writeText("hello over webdav")

        client().use { dav ->
            val entries = dav.list("/")
            assertEquals(setOf("photos", "notes.txt"), entries.map { it.name }.toSet())
            assertTrue(entries.first { it.name == "photos" }.isDir)
            assertEquals(0L, entries.first { it.name == "photos" }.size)
            assertEquals(17L, entries.first { it.name == "notes.txt" }.size)
            assertTrue(entries.first { it.name == "notes.txt" }.modifiedMs > 0)
            assertEquals("/photos", entries.first { it.name == "photos" }.path)
        }
    }

    @Test
    fun `downloads and uploads`() {
        File(root, "notes.txt").writeText("hello over webdav")

        client().use { dav ->
            val got = File(root.parentFile, "dav-got-${System.nanoTime()}.txt")
            dav.download("/notes.txt", got)
            assertEquals("hello over webdav", got.readText())
            got.delete()

            val send = File(root.parentFile, "dav-send-${System.nanoTime()}.txt")
                .apply { writeText("from the phone") }
            dav.upload(send, "/up.txt")
            assertEquals("from the phone", File(root, "up.txt").readText())
            send.delete()
        }
    }

    @Test
    fun `names needing encoding survive both ways`() {
        client().use { dav ->
            val send = File(root.parentFile, "s-${System.nanoTime()}.txt")
                .apply { writeText("spaced") }
            dav.upload(send, "/two words.txt")
            assertEquals("spaced", File(root, "two words.txt").readText())

            val listed = dav.list("/").first { it.name == "two words.txt" }
            assertEquals("/two words.txt", listed.path)

            dav.delete("/two words.txt", isDir = false)
            assertFalse(File(root, "two words.txt").exists())
            send.delete()
        }
    }

    @Test
    fun `creates renames and deletes collections`() {
        client().use { dav ->
            dav.makeDirectory("/made")
            assertTrue(File(root, "made").isDirectory)

            dav.rename("/made", "/renamed")
            assertTrue(File(root, "renamed").isDirectory)
            assertFalse(File(root, "made").exists())

            dav.delete("/renamed", isDir = true)
            assertFalse(File(root, "renamed").exists())
        }
    }

    @Test
    fun `http codes are explained rather than repeated`() {
        client().use { dav ->
            val missing = runCatching {
                dav.download("/nothing.txt", File(root.parentFile, "x-${System.nanoTime()}"))
            }
            assertTrue(
                "should say it is gone, said: ${missing.exceptionOrNull()?.message}",
                missing.exceptionOrNull()?.message?.contains("no longer there") == true,
            )
        }

        val refused = runCatching { client(password = "nope").use { it.list("/") } }
        assertTrue(
            "should blame the password, said: ${refused.exceptionOrNull()?.message}",
            refused.exceptionOrNull()?.message?.contains("not accepted") == true,
        )
    }

    // --- The mock server ----------------------------------------------------

    private fun local(rawPath: String): File =
        File(root, URLDecoder.decode(rawPath, "UTF-8").trimStart('/'))

    private fun handle(request: RecordedRequest): MockResponse {
        if (request.getHeader("Authorization") != credential) {
            return MockResponse()
                .setResponseCode(401)
                .addHeader("WWW-Authenticate", "Basic realm=\"test\"")
        }

        val target = local(request.path.orEmpty().substringBefore('?'))
        return when (request.method) {
            "PROPFIND" ->
                if (target.exists()) {
                    MockResponse()
                        .setResponseCode(207)
                        .addHeader("Content-Type", "application/xml; charset=utf-8")
                        .setBody(propfind(target))
                } else {
                    MockResponse().setResponseCode(404)
                }

            "GET" ->
                if (target.isFile) {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(target.readBytes()))
                } else {
                    MockResponse().setResponseCode(404)
                }

            "PUT" -> {
                target.parentFile?.mkdirs()
                target.writeBytes(request.body.readByteArray())
                MockResponse().setResponseCode(201)
            }

            "DELETE" -> {
                if (!target.exists()) {
                    MockResponse().setResponseCode(404)
                } else {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                    MockResponse().setResponseCode(204)
                }
            }

            "MKCOL" ->
                if (target.exists()) {
                    MockResponse().setResponseCode(405)
                } else {
                    target.mkdirs()
                    MockResponse().setResponseCode(201)
                }

            "MOVE" -> {
                val destination = request.getHeader("Destination")
                if (destination == null) {
                    MockResponse().setResponseCode(400)
                } else {
                    target.renameTo(local(URI(destination).rawPath))
                    MockResponse().setResponseCode(201)
                }
            }

            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun propfind(target: File): String {
        val entries = mutableListOf(target)
        if (target.isDirectory) entries += target.listFiles().orEmpty().sortedBy { it.name }

        val rfc1123 = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

        return buildString {
            // "D:" rather than "d:", to prove the parse is by namespace.
            append("""<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""")
            for (file in entries) {
                val relative = "/" + file.relativeTo(root).path.replace(File.separatorChar, '/')
                val href = (if (relative == "/.") "/" else relative).replace(" ", "%20")
                append("<D:response><D:href>").append(href).append("</D:href>")
                append("<D:propstat><D:prop>")
                if (file.isDirectory) {
                    append("<D:resourcetype><D:collection/></D:resourcetype>")
                } else {
                    append("<D:resourcetype/><D:getcontentlength>")
                        .append(file.length()).append("</D:getcontentlength>")
                }
                append("<D:getlastmodified>")
                    .append(rfc1123.format(Instant.ofEpochMilli(file.lastModified())))
                    .append("</D:getlastmodified>")
                append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
            }
            append("</D:multistatus>")
        }
    }
}
