package com.filemanager.app.data.ftpd

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * How the built-in server is set up.
 *
 * The default port is 2121, not 21. Binding anything below 1024 needs root,
 * which an app does not have, so a default of 21 would fail on every device
 * with an error about permissions that has nothing to do with the app's.
 */
data class FtpServerConfig(
    val port: Int = DEFAULT_PORT,
    val username: String = "android",
    val password: String = "",
    val anonymous: Boolean = false,
    /** Refuse uploads, deletes and renames. On by default: handing the whole
     *  of shared storage to anything on the network is the bigger surprise. */
    val readOnly: Boolean = true,
    /**
     * What the server shows as its root. Clients see this as "/".
     *
     * Chosen by the user, so it can be a single folder rather than the whole
     * of storage - which is the difference between sharing the camera roll and
     * sharing everything on the phone.
     */
    val rootPath: String = Environment.getExternalStorageDirectory().absolutePath,
) {
    fun validate(): String? = when {
        port !in 1024..65535 -> "Port must be between 1024 and 65535"
        !anonymous && username.isBlank() -> "Enter a user name, or allow anonymous access"
        !anonymous && password.isBlank() -> "Enter a password, or allow anonymous access"
        // Checked here rather than left to the server, which reports a missing
        // home directory as a failed login long after the folder was chosen -
        // by which time a card could have been removed, or the folder deleted
        // from the browser.
        rootPath.isBlank() -> "Choose a folder to share"
        !File(rootPath).isDirectory -> "\"$rootPath\" is not a folder any more"
        else -> null
    }

    companion object {
        const val DEFAULT_PORT = 2121
    }
}

/** Stores the server settings between runs. */
class FtpServerSettings(context: Context) {

    private val prefs = context.getSharedPreferences("ftp_server", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<FtpServerConfig> = _config.asStateFlow()

    fun save(config: FtpServerConfig) {
        prefs.edit()
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USER, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putBoolean(KEY_ANONYMOUS, config.anonymous)
            .putBoolean(KEY_READ_ONLY, config.readOnly)
            .putString(KEY_ROOT, config.rootPath)
            .apply()
        _config.value = config
    }

    private fun read(): FtpServerConfig {
        val fallback = FtpServerConfig()
        return FtpServerConfig(
            port = prefs.getInt(KEY_PORT, fallback.port),
            username = prefs.getString(KEY_USER, fallback.username) ?: fallback.username,
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            anonymous = prefs.getBoolean(KEY_ANONYMOUS, fallback.anonymous),
            readOnly = prefs.getBoolean(KEY_READ_ONLY, fallback.readOnly),
            rootPath = prefs.getString(KEY_ROOT, fallback.rootPath) ?: fallback.rootPath,
        )
    }

    private companion object {
        const val KEY_PORT = "port"
        const val KEY_USER = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_ANONYMOUS = "anonymous"
        const val KEY_READ_ONLY = "read_only"
        const val KEY_ROOT = "root"
    }
}
