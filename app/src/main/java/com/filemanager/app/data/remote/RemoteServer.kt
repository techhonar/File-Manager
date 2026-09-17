package com.filemanager.app.data.remote

import java.util.UUID

/**
 * Which protocol a saved server speaks.
 *
 * The default port is the one the protocol is registered for, offered in the
 * form so nobody has to look it up, and overridable because plenty of servers
 * do not sit on it.
 */
enum class RemoteType(val label: String, val defaultPort: Int) {
    FTP("FTP", 21),
    SFTP("SFTP", 22),
    SMB("SMB", 445),
    WEBDAV("WebDAV", 80),
    ;

    /** Whether [RemoteServer.secure] means anything for this protocol. */
    val hasSecureOption: Boolean get() = this == FTP || this == WEBDAV

    /** Only SMB names a share separately from the path inside it. */
    val hasShare: Boolean get() = this == SMB
}

/**
 * One saved network location.
 *
 * Stored in the app's own preferences, which on an unrooted device only this
 * app can read. That is the whole of the protection: the password is not
 * encrypted at rest, because encrypting it with a key kept beside it would
 * only look like security. Anyone with the device unlocked and root, or a
 * backup of the app's data, can read it - which is the same position every
 * other client that can reconnect without prompting is in.
 */
data class RemoteServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: RemoteType = RemoteType.FTP,
    val host: String = "",
    val port: Int = RemoteType.FTP.defaultPort,
    val username: String = "",
    val password: String = "",
    /** SMB only. The share to connect to, without slashes. */
    val share: String = "",
    /** Directory to open when the server is entered. */
    val basePath: String = "/",
    val anonymous: Boolean = false,
    /** FTPS for FTP, https for WebDAV. SFTP is always encrypted; SMB negotiates. */
    val secure: Boolean = false,
) {
    /** What to show in a list. Falls back to the host so a row is never blank. */
    val label: String get() = name.ifBlank { host }.ifBlank { "Unnamed" }

    /** A one-line description of where this points, for a list subtitle. */
    val summary: String
        get() = buildString {
            append(type.label)
            append(" · ")
            if (username.isNotBlank() && !anonymous) {
                append(username)
                append('@')
            }
            append(host)
            if (port != type.defaultPort) {
                append(':')
                append(port)
            }
            if (type.hasShare && share.isNotBlank()) {
                append('/')
                append(share)
            }
        }

    /**
     * What is wrong with this definition, or null when it is usable.
     *
     * Checked before saving rather than on connecting, so the form can say
     * which field is at fault instead of the connection failing later with
     * something from deep inside a protocol library.
     */
    fun validate(): String? = when {
        host.isBlank() -> "Enter a host name or IP address"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        type.hasShare && share.isBlank() -> "Enter the share name"
        !anonymous && username.isBlank() -> "Enter a user name, or choose anonymous"
        else -> null
    }
}
