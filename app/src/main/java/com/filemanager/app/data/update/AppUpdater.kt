package com.filemanager.app.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.filemanager.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** The app's home on GitHub, as owner/name: its releases, and its source. */
const val GITHUB_REPOSITORY = "techhonar/File-Manager"

/**
 * Update App: find the newest release on GitHub, download its APK, and hand it
 * to Android's installer.
 *
 * The installer still asks - no app can update itself silently - but that is
 * the only step left to the user. Held for the whole process, so a download
 * carries on if the screen that started it goes away.
 */
class AppUpdater(private val context: Context) {

    sealed interface State {
        /** Nothing going on; no dialog. */
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        /** [total] is 0 when the server does not say how big the file is. */
        data class Downloading(val version: String, val done: Long, val total: Long) : State
        /** Downloaded, waiting for the screen to open the installer. */
        data class Ready(val version: String, val apk: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Check, and download if there is something newer. A second tap while busy is ignored. */
    fun update() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Checking
            try {
                val release = latestRelease()
                if (!isNewerVersion(release.version, BuildConfig.VERSION_NAME)) {
                    _state.value = State.UpToDate(BuildConfig.VERSION_NAME)
                    return@launch
                }
                _state.value = State.Ready(release.version, download(release))
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(describe(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    /** Close whatever the dialog is showing once it has been read or acted on. */
    fun dismiss() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    /** What opens Android's installer on [apk]. */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private data class Release(val version: String, val url: String, val name: String, val size: Long)

    private fun latestRelease(): Release {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$GITHUB_REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            response.body?.string() ?: throw IOException("GitHub sent nothing back")
        }
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
            ?: throw IOException("The latest release has no APK in it")
        return Release(
            version = json.getString("tag_name").removePrefix("v"),
            url = apk.getString("browser_download_url"),
            name = apk.getString("name"),
            size = apk.optLong("size", 0L),
        )
    }

    /**
     * Into the app's cache, where the file provider can hand it to the
     * installer. Written aside and renamed when whole, so a download cut short
     * is never taken for the update.
     */
    private suspend fun download(release: Release): File {
        val dir = File(context.cacheDir, "updates").apply {
            // Whatever an earlier update left behind; the installer has long
            // since copied it.
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, release.name)
        val partial = File(dir, "${release.name}.part")
        try {
            client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The download failed (${response.code})")
                val body = response.body ?: throw IOException("The download was empty")
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                var done = 0L
                var reported = 0L
                _state.value = State.Downloading(release.version, 0, total)
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            // Often enough to move the bar smoothly, not so
                            // often that it redraws for every 64 KB.
                            if (done - reported >= PROGRESS_STEP) {
                                reported = done
                                _state.value = State.Downloading(release.version, done, total)
                            }
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("Could not save the download")
            return target
        } finally {
            partial.delete()
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is UnknownHostException -> "No internet connection"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
    }

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val PROGRESS_STEP = 256L * 1024
    }
}
