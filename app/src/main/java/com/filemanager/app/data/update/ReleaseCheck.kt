package com.filemanager.app.data.update

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * What one check leaves for the next: the ETag of GitHub's last answer, the
 * version it named and the changes its notes listed, and the newest version
 * the user has been told of.
 */
interface UpdateMemory {
    var etag: String?
    var latest: String?
    var latestChanges: List<String>
    var announced: String?
}

/** [UpdateMemory] in the app's preferences, so it outlives the process. */
class PrefsUpdateMemory(context: Context) : UpdateMemory {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    override var etag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit().putString(KEY_ETAG, value).apply()

    override var latest: String?
        get() = prefs.getString(KEY_LATEST, null)
        set(value) = prefs.edit().putString(KEY_LATEST, value).apply()

    // One per line: a title is a line of the notes, so never holds one.
    override var latestChanges: List<String>
        get() = prefs.getString(KEY_LATEST_CHANGES, null)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit().putString(KEY_LATEST_CHANGES, value.joinToString("\n")).apply()

    override var announced: String?
        get() = prefs.getString(KEY_ANNOUNCED, null)
        set(value) = prefs.edit().putString(KEY_ANNOUNCED, value).apply()

    private companion object {
        const val KEY_ETAG = "etag"
        const val KEY_LATEST = "latest"
        const val KEY_LATEST_CHANGES = "latest_changes"
        const val KEY_ANNOUNCED = "announced"
    }
}

/**
 * Asking GitHub which version its latest release is, cheaply enough to do
 * every few minutes.
 *
 * Each request carries the ETag of the last answer, so when nothing has
 * changed - nearly every time - GitHub says so in a 304 with no release in
 * it. That saves the data, not the count: GitHub allows sixty requests an
 * hour from one address without signing in, 304s included (seen on
 * 2026-10-10, whatever its docs say), and every ten minutes is six of
 * them. A check refused for being over is simply missed.
 */
class ReleaseCheck(
    private val client: OkHttpClient,
    private val memory: UpdateMemory,
    private val url: String = "https://api.github.com/repos/$GITHUB_REPOSITORY/releases/latest",
) {
    /**
     * The latest release's version, once it has an APK to download - until
     * then it is not an update anyone can take. Null too when GitHub could
     * not be asked or would not say: offline, out of requests for the hour,
     * or an answer that makes no sense.
     */
    fun latestVersion(): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .apply { memory.etag?.let { header("If-None-Match", it) } }
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_NOT_MODIFIED -> memory.latest
                    !response.isSuccessful -> null
                    else -> {
                        val release = JSONObject(response.body?.string().orEmpty())
                        val assets = release.getJSONArray("assets")
                        val hasApk = (0 until assets.length()).any {
                            assets.getJSONObject(it).getString("name").endsWith(".apk", ignoreCase = true)
                        }
                        val version = release.getString("tag_name").removePrefix("v").takeIf { hasApk }
                        // Kept with its ETag either way: a release still
                        // waiting for its APK changes when the APK arrives,
                        // and so does its ETag. The changes are kept too, as
                        // the next answer is a 304 that does not repeat them.
                        memory.latest = version
                        memory.latestChanges = if (version == null) emptyList() else releaseChanges(release.optString("body"))
                        memory.etag = response.header("ETag")
                        version
                    }
                }
            }
        }.getOrNull()
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
    }
}

/**
 * The version to tell the user about now, if any: newer than [installed],
 * and not one they have been told of already - a check every ten minutes
 * must not mean a notification every ten minutes.
 */
fun versionToAnnounce(latest: String?, installed: String, announced: String?): String? =
    latest?.takeIf { isNewerVersion(it, installed) && it != announced }

/**
 * The changes a release's [notes] list, by title: each is a line of its own,
 * in bold, as tools/release_notes.py writes them. Those behind the scenes are
 * left out, being of no use to anyone deciding whether to update.
 */
fun releaseChanges(notes: String): List<String> {
    var behindTheScenes = false
    val titles = mutableListOf<String>()
    for (line in notes.lineSequence().map { it.trim() }) {
        if (line.startsWith("### ")) {
            behindTheScenes = line.removePrefix("### ").trim().equals("Behind the scenes", ignoreCase = true)
        } else if (!behindTheScenes) {
            // A whole line in bold, so not "**Full changelog:** ..." after them.
            BOLD_LINE.matchEntire(line)?.let { titles += it.groupValues[1].trim() }
        }
    }
    return titles
}

private val BOLD_LINE = Regex("""\*\*([^*]+)\*\*""")
