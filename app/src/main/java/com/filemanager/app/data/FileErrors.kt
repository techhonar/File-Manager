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
    is FileException.Io -> message?.takeIf { it.isNotBlank() } ?: fallback
    else -> message?.takeIf { it.isNotBlank() } ?: fallback
}

/** True when the archive was locked and the supplied password was not right. */
fun Throwable.isWrongPassword(): Boolean = this is FileException.WrongPassword
