package com.filemanager.app.data.update

/**
 * Whether [candidate] is a later version than [current].
 *
 * Compared number by number, so 0.4.10 comes after 0.4.9 - which comparing
 * the text gets backwards. A leading "v", as release tags carry, is ignored,
 * and a missing part counts as zero, so 0.5 and 0.5.0 are the same version.
 *
 * A candidate that is not numbers and dots is never newer: a mistyped tag
 * must not offer itself as an update. A current version that is not - a
 * local build named something else - is taken as older than any release.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val offered = versionParts(candidate) ?: return false
    val installed = versionParts(current) ?: return true
    for (i in 0 until maxOf(offered.size, installed.size)) {
        val a = offered.getOrElse(i) { 0 }
        val b = installed.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun versionParts(version: String): List<Int>? {
    val parts = version.trim().removePrefix("v").removePrefix("V").split('.')
    return parts.map { it.toIntOrNull() ?: return null }
}
