package com.filemanager.app.data.remote

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV - Nextcloud, ownCloud, a Synology, an Apache with dav_fs on.
 *
 * Spoken directly over HTTP rather than through a WebDAV library: the four
 * verbs this needs are a few lines each on top of the HTTP client the app
 * already ships for image loading, and the alternative is another dependency
 * for what amounts to one XML parse.
 */
internal class WebDavRemoteClient(private val server: RemoteServer) : RemoteClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Sent on every request rather than waiting to be challenged, which
     *  halves the round trips and is what every WebDAV client does. */
    private val credential: String? =
        if (server.anonymous || server.username.isBlank()) {
            null
        } else {
            Credentials.basic(server.username, server.password)
        }

    private val base: HttpUrl = HttpUrl.Builder()
        .scheme(if (server.secure) "https" else "http")
        .host(server.host)
        .port(server.port)
        .build()

    override fun list(path: String): List<RemoteEntry> {
        val dir = RemotePaths.normalise(path)
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = build(dir) {
            method("PROPFIND", body)
            // One level. Without it a server may answer for the whole subtree,
            // which on a large share is a reply nobody asked for.
            header("Depth", "1")
        }

        return call(request, "list this folder") { response ->
            val xml = response.body?.byteStream()
                ?: throw RemoteException("The server sent an empty reply")

            // Namespace-aware: servers disagree about the prefix (d:, D:, dav:)
            // and matching on the literal tag name would work with some and
            // silently return nothing for others.
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = factory.newDocumentBuilder().parse(xml)
            val responses = document.getElementsByTagNameNS(DAV_NS, "response")

            (0 until responses.length).mapNotNull { index ->
                val element = responses.item(index) as? Element ?: return@mapNotNull null
                entryFrom(element, dir)
            }
        }
    }

    /**
     * One `<response>`, or null when it is the folder being listed.
     *
     * A PROPFIND at depth 1 always describes the collection itself first, and
     * including it would put every folder inside itself.
     */
    private fun entryFrom(element: Element, dir: String): RemoteEntry? {
        val href = element.firstText(DAV_NS, "href") ?: return null
        val resolved = base.resolve(href) ?: return null
        val path = "/" + resolved.pathSegments.filter { it.isNotEmpty() }.joinToString("/")
        if (RemotePaths.normalise(path) == dir) return null

        val isDir = element.getElementsByTagNameNS(DAV_NS, "collection").length > 0
        val size = element.firstText(DAV_NS, "getcontentlength")?.trim()?.toLongOrNull() ?: 0L
        val modified = element.firstText(DAV_NS, "getlastmodified")?.let(::parseHttpDate) ?: 0L

        return RemoteEntry(
            name = RemotePaths.name(path),
            path = RemotePaths.normalise(path),
            size = if (isDir) 0L else size,
            isDir = isDir,
            modifiedMs = modified,
        )
    }

    override fun download(path: String, to: File) {
        call(build(path) { get() }, "download this file") { response ->
            val stream = response.body?.byteStream()
                ?: throw RemoteException("The server sent no content")
            stream.use { input -> to.outputStream().use { output -> input.copyTo(output) } }
        }
    }

    override fun upload(from: File, path: String) {
        val body = from.asRequestBody("application/octet-stream".toMediaType())
        call(build(path) { put(body) }, "upload this file") { }
    }

    override fun delete(path: String, isDir: Boolean) {
        call(build(path) { delete() }, "delete this") { }
    }

    override fun makeDirectory(path: String) {
        call(build(path) { method("MKCOL", null) }, "create this folder") { }
    }

    override fun rename(from: String, to: String) {
        val destination = url(to).toString()
        val request = build(from) {
            method("MOVE", null)
            header("Destination", destination)
            // Without this a server is entitled to refuse rather than replace,
            // and the app has already asked the user about overwriting.
            header("Overwrite", "T")
        }
        call(request, "rename this") { }
    }

    /**
     * HTTP is connectionless, so there is nothing to have gone stale.
     *
     * Every call stands on its own and OkHttp manages its own pool, so a
     * client that has sat idle is as good as a fresh one.
     */
    override fun isUsable(): Boolean = true

    override fun close() {
        // The dispatcher holds non-daemon threads that would otherwise keep
        // running after the server is forgotten.
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    private fun url(path: String): HttpUrl {
        val segments = RemotePaths.normalise(path).trim('/')
        val builder = base.newBuilder()
        if (segments.isNotEmpty()) builder.addPathSegments(segments)
        return builder.build()
    }

    private fun build(path: String, block: Request.Builder.() -> Unit): Request =
        Request.Builder()
            .url(url(path))
            .apply { credential?.let { header("Authorization", it) } }
            .apply(block)
            .build()

    private fun <T> call(request: Request, what: String, body: (Response) -> T): T = try {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RemoteException("Could not $what: ${explain(response.code)}")
            }
            body(response)
        }
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException("Could not $what: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** HTTP codes the user can do something about, said in their own terms. */
    private fun explain(code: Int): String = when (code) {
        401, 403 -> "the user name or password was not accepted"
        404 -> "it is no longer there"
        405 -> "the server does not allow that here"
        409 -> "the folder above it does not exist"
        507 -> "the server is out of space"
        else -> "the server answered $code"
    }

    private fun Element.firstText(namespace: String, tag: String): String? =
        getElementsByTagNameNS(namespace, tag).item(0)?.textContent

    /** RFC 1123, which is what getlastmodified is defined to carry. */
    private fun parseHttpDate(value: String): Long = runCatching {
        SimpleDateFormat(HTTP_DATE, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(value.trim())
            ?.time
            ?: 0L
    }.getOrDefault(0L)

    private companion object {
        const val DAV_NS = "DAV:"
        const val HTTP_DATE = "EEE, dd MMM yyyy HH:mm:ss zzz"
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 60L

        /** Only the three properties this shows. Asking for everything makes
         *  a server assemble metadata nothing here reads. */
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
