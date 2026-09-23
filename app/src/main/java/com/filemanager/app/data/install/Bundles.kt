package com.filemanager.app.data.install

import android.content.pm.PackageInstaller
import java.io.File
import java.util.zip.ZipFile

/**
 * Split-APK bundles: an app delivered as a zip of APKs.
 *
 * An app built as an App Bundle is published as a base APK plus splits - one
 * per processor type, screen density and language - and Play sends each phone
 * only the pieces it needs. Sites that redistribute such apps zip the pieces
 * together: APKPure's .xapk, SAI's .apks and APKMirror's .apkm all keep the
 * base and its splits side by side at the top of the archive, and an .xapk can
 * add a game's data files under Android/obb. The system installer opens a
 * single APK only, so tapping one of these used to do nothing at all.
 */
val BUNDLE_EXTENSIONS = setOf("xapk", "apks", "apkm")

/** Whether [fileName] names one of the bundles above. */
fun isBundle(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in BUNDLE_EXTENSIONS

/** A game data file in a bundle, and the app it belongs to. */
data class GameData(
    /** The entry's name inside the bundle. */
    val entry: String,
    /** Whose Android/obb folder it belongs in. */
    val packageName: String,
    /** Its name within that folder. */
    val fileName: String,
)

/** What installing a bundle involves. */
data class BundlePlan(
    /** Entry names of the APKs - the base and every split, installed as one. */
    val apks: List<String>,
    val gameData: List<GameData>,
)

/**
 * Which of a bundle's entries to install, and which are game data.
 *
 * APKs are taken from the top of the archive only. That is where every one of
 * these formats keeps them, and an APK anywhere else is something else:
 * bundletool's .apks keeps whole APKs for old phones in a folder of their own,
 * and installing one of those alongside the splits would fail.
 *
 * Game data is taken only from Android/obb/<package>/ and only as plain files
 * in that folder, so no entry's name can place anything outside it.
 *
 * Null when there is no APK at all, which is what any other zip given one of
 * these names looks like.
 */
fun planBundle(entryNames: List<String>): BundlePlan? {
    val apks = entryNames
        .filter { name ->
            '/' !in name && '\\' !in name && name.endsWith(".apk", ignoreCase = true)
        }
        .sorted()
    if (apks.isEmpty()) return null

    val gameData = entryNames.mapNotNull { name ->
        GAME_DATA.matchEntire(name)?.let { match ->
            GameData(entry = name, packageName = match.groupValues[1], fileName = match.groupValues[2])
        }
    }
    return BundlePlan(apks, gameData)
}

/**
 * Android/obb/<package>/<file>.obb. A package name is dotted segments, each
 * starting with a letter - which also rules out "." and "..".
 */
private val GAME_DATA = Regex(
    """(?i)android/obb/([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)/([^/\\]+\.obb)""",
)

/**
 * Unpack [gameData] from [zip] into [dir], one folder per app named after its
 * package - the folder that belongs in Android/obb. Returns those folders.
 *
 * Each file is written aside and renamed into place, so an unpack cut short -
 * a full disk, say - leaves no truncated file under the real name for the game
 * to fail on later. Throws on the first file that cannot be written.
 */
fun unpackGameData(zip: ZipFile, gameData: List<GameData>, dir: File): List<File> =
    gameData.groupBy { it.packageName }.map { (packageName, files) ->
        val folder = File(dir, packageName)
        folder.mkdirs()
        for (file in files) {
            val target = File(folder, file.fileName)
            val partial = File(folder, "${file.fileName}.part")
            try {
                zip.getInputStream(zip.getEntry(file.entry)).use { input ->
                    partial.outputStream().use { input.copyTo(it) }
                }
                check(partial.renameTo(target)) { "could not write ${target.name}" }
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }
        folder
    }

/**
 * What to tell the user once the installer has finished with [label], or
 * null when there is nothing to say.
 */
fun installOutcomeMessage(status: Int, label: String, detail: String?): String? = when (status) {
    PackageInstaller.STATUS_SUCCESS -> "Installed $label"
    // The user said no in the system's own dialog; they know.
    PackageInstaller.STATUS_FAILURE_ABORTED -> null
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installing $label is blocked on this phone"
    PackageInstaller.STATUS_FAILURE_CONFLICT ->
        "$label conflicts with an app already installed, usually one signed by someone else"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "$label does not work on this phone"
    PackageInstaller.STATUS_FAILURE_INVALID -> "$label is damaged or incomplete"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space to install $label"
    else -> "Could not install $label" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
}
