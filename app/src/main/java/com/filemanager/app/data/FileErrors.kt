package com.filemanager.app.data

import uniffi.filemanager_core.FileException

/**
 * Readable text for an error from the native core.
 *
 * The generated exceptions carry no useful `message`: a variant with no fields
 * renders as the empty string, so showing `error.message` produced a blank
 * snackbar and looked as though nothing had happened at all. Match on the type
 * instead.
 */
fun Throwable.userMessage(fallback: String): String = when (this) {
    is FileException.WrongPassword -> "That password did not work"
    is FileException.PasswordRequired -> "This archive needs a password"
    is FileException.NotFound -> "That file is no longer there"
    is FileException.PermissionDenied -> "Permission denied"
    is FileException.AlreadyExists -> "Something with that name is already there"
    is FileException.NotADirectory -> "That is not a folder"
    is FileException.Archive -> "This archive could not be read"
    is FileException.Cancelled -> "Cancelled"
    is FileException.IntoItself -> "A folder cannot be copied or moved into itself"
    is FileException.ReadOnly -> "This storage is read-only"
    is FileException.NoSpace -> "There isn't enough space"
    is FileException.TooLarge -> "Too big for this storage: a FAT32 drive takes files up to 4 GB"
    is FileException.Io -> osReason(detail)?.let { "$fallback: $it" } ?: fallback
    else -> message?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * What the system said went wrong, from the core's "path: reason (os error
 * n)": "Input/output error", say, for a drive pulled out partway. Shown
 * after what was being done, rather than the whole raw text with its path,
 * which came out as "detail=/mnt/media_rw/...".
 */
internal fun osReason(detail: String): String? =
    detail.substringAfterLast(": ").replace(Regex("""\s*\(os error \d+\)$"""), "").trim().ifEmpty { null }

/** True when the archive was locked and the supplied password was not right. */
fun Throwable.isWrongPassword(): Boolean = this is FileException.WrongPassword
