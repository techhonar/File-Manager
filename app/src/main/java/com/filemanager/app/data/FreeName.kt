package com.filemanager.app.data

import java.io.File

/**
 * The first name in [dir] that nothing is using: "photos", then "photos (1)",
 * "photos (2)" and so on.
 *
 * For the places the app makes something new next to what is already there.
 * Extracting an archive went into a folder named after it whether or not that
 * folder already existed, and wrote into it file by file - replacing any of
 * the user's own files that happened to share a name with one in the archive.
 * Compressing twice was refused outright because the zip from the first time
 * was in the way.
 *
 * [extension] without the dot, or empty for a folder.
 */
fun freeName(dir: File, base: String, extension: String = ""): File {
    val suffix = if (extension.isEmpty()) "" else ".$extension"
    val first = File(dir, "$base$suffix")
    if (!first.exists()) return first
    return generateSequence(1) { it + 1 }
        .map { File(dir, "$base ($it)$suffix") }
        .first { !it.exists() }
}
