package com.filemanager.app.data.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Installs the split-APK bundles described in Bundles.kt.
 *
 * Through an installer session: every APK in the bundle written into one
 * session and committed together, which is what installing them as one app
 * means. The user still decides - committing hands over to the system's own
 * confirmation, as tapping a single APK does, and the first time that also
 * asks to allow installs from this app.
 *
 * One per process: an install outlives whichever screen it was started from.
 */
class BundleInstaller(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    /** Bundles being prepared, so a second tap does not start a second install. */
    private val preparing = ConcurrentHashMap.newKeySet<String>()

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * Something to explain at more length than a toast holds - where a game's
     * data was left and why. Null when there is nothing to say.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    /** Start installing the bundle at [path]. Returns at once. */
    fun install(path: String) {
        val bundle = File(path)
        if (!preparing.add(bundle.path)) return
        tell("Preparing ${bundle.name}…")
        scope.launch {
            try {
                stage(bundle)
            } catch (e: Exception) {
                tell("Could not install ${bundle.name}: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                preparing.remove(bundle.path)
            }
        }
    }

    private fun stage(bundle: File) {
        ZipFile(bundle).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            val plan = planBundle(names)
            if (plan == null) {
                tell("${bundle.name} has no app in it to install")
                return
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Lets the installer refuse up front for want of space, rather than
            // partway through writing.
            params.setSize(plan.apks.sumOf { zip.getEntry(it).size.coerceAtLeast(0L) })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }

            val sessionId = installer.createSession(params)
            try {
                installer.openSession(sessionId).use { session ->
                    plan.apks.forEachIndexed { index, name ->
                        val entry = zip.getEntry(name)
                        zip.getInputStream(entry).use { input ->
                            // Named by position: the installer reads which split
                            // is which from inside each APK, and an entry's own
                            // name could hold anything.
                            session.openWrite("$index.apk", 0, entry.size).use { output ->
                                input.copyTo(output)
                                session.fsync(output)
                            }
                        }
                    }
                    session.commit(outcomeReceiver(sessionId, bundle))
                }
            } catch (e: Exception) {
                runCatching { installer.abandonSession(sessionId) }
                throw e
            }
        }
    }

    /**
     * Called once the installer reports the app in [bundlePath] installed.
     *
     * Only then is its game data unpacked, if it has any: an install the user
     * cancels should not leave gigabytes of it behind.
     */
    fun installed(bundlePath: String) {
        val bundle = File(bundlePath)
        scope.launch {
            try {
                ZipFile(bundle).use { zip ->
                    val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                    val gameData = planBundle(names)?.gameData.orEmpty()
                    if (gameData.isNotEmpty()) placeGameData(zip, gameData, bundle)
                }
            } catch (e: Exception) {
                // Moved or deleted since the install began: nothing to unpack from.
                tell("Could not unpack the game data from ${bundle.name}")
            }
        }
    }

    /**
     * A game's data belongs in Android/obb/<package>, and Android lets only the
     * phone's own file manager write there. Since Android 11 an installer also
     * needs a storage permission that apps targeting Android 13 or later can no
     * longer be given, so no app like this one can put it in place. It is
     * unpacked beside the bundle instead, and the user told where to move it.
     */
    private fun placeGameData(zip: ZipFile, gameData: List<GameData>, bundle: File) {
        // Beside the bundle when that is on internal storage, where the data has
        // to end up, so moving it is instant. Otherwise into Download: a bundle
        // opened from a network server waits in this app's private cache, out
        // of the user's reach, and one on an SD card would have to cross to
        // internal storage anyway.
        val shared = Environment.getExternalStorageDirectory()
        val beside = bundle.absoluteFile.parentFile?.takeIf { it.startsWith(shared) }
        val into = beside
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folders = try {
            unpackGameData(zip, gameData, into)
        } catch (e: Exception) {
            _notice.value = "${bundle.nameWithoutExtension} needs its game data, which could not " +
                "be unpacked (${e.message ?: e.javaClass.simpleName}). Without it the game may " +
                "download the data itself, or not start."
            return
        }
        _notice.value = "${bundle.nameWithoutExtension} needs its game data in Android/obb, " +
            "where only your phone's own Files app can put it. It has been unpacked to " +
            folders.joinToString(" and ") { shown(it) } + " - move that folder into " +
            "Android/obb with the Files app before opening the game."
    }

    /** A path as the user finds it: from the top of internal storage. */
    private fun shown(file: File): String {
        val root = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
        return file.path.removePrefix(root)
    }

    private fun outcomeReceiver(sessionId: Int, bundle: File): IntentSender {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .putExtra(InstallResultReceiver.EXTRA_LABEL, bundle.nameWithoutExtension)
            .putExtra(InstallResultReceiver.EXTRA_BUNDLE, bundle.path)
        // Mutable because the installer writes the outcome into it - and so
        // explicit, which a mutable one has to be.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        ).intentSender
    }

    private fun tell(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }
}
