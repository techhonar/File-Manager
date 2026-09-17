package com.filemanager.app.data.ftpd

import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.listener.ListenerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.ServerSocket

/**
 * Starts and stops the built-in FTP server.
 *
 * Holds no Android types, so the service owns the lifecycle and this owns the
 * protocol. Everything here is synchronised: start and stop arrive from the
 * service's main thread while the server's own threads are running.
 */
class FtpServerController {

    private var server: FtpServer? = null

    /**
     * The config the running server was started with, or null when stopped.
     *
     * A flow rather than a getter because two things watch it - the settings
     * screen and the notification - and neither is in a position to poll.
     */
    private val _running = MutableStateFlow<FtpServerConfig?>(null)
    val running: StateFlow<FtpServerConfig?> = _running.asStateFlow()

    val isRunning: Boolean
        @Synchronized get() = server?.isStopped == false

    /**
     * Why the last start attempt failed, or null.
     *
     * The service is what calls [start], and a service has nowhere to show an
     * error - so it records it here and the screen picks it up. Without this a
     * failed start does nothing visible at all: the button is pressed, no
     * server appears, and nothing says why.
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun reportFailure(message: String) {
        _lastError.value = message
    }

    fun clearFailure() {
        _lastError.value = null
    }

    /**
     * Starts the server, replacing any already running.
     *
     * Throws [FtpServerException] with something worth showing rather than
     * letting the library's own exception out - "Failed to bind to address"
     * does not tell anyone which port to change.
     */
    @Synchronized
    fun start(config: FtpServerConfig) {
        stop()

        config.validate()?.let { throw FtpServerException(it) }

        // Checked before handing the port to the library, which reports a bind
        // failure as a generic startup error several frames deep.
        if (!isPortFree(config.port)) {
            throw FtpServerException(
                "Port ${config.port} is already in use. Try another one.",
            )
        }

        val listener = ListenerFactory().apply {
            port = config.port
            idleTimeout = IDLE_SECONDS
        }

        val factory = FtpServerFactory().apply {
            addListener("default", listener.createListener())
            userManager = SingleUserManager(config)
            // Serves real paths. The app holds MANAGE_EXTERNAL_STORAGE, so the
            // whole of shared storage is reachable; createHome is off because
            // the root is somewhere that already exists and silently creating
            // a directory for a mistyped path would hide the mistake.
            fileSystem = NativeFileSystemFactory().apply { isCreateHome = false }
            connectionConfig = ConnectionConfigFactory().apply {
                isAnonymousLoginEnabled = config.anonymous
                maxLogins = MAX_LOGINS
                maxAnonymousLogins = if (config.anonymous) MAX_LOGINS else 0
                maxLoginFailures = MAX_LOGIN_FAILURES
                loginFailureDelay = LOGIN_FAILURE_DELAY_MS
            }.createConnectionConfig()
        }

        val started = factory.createServer()
        try {
            started.start()
        } catch (e: Exception) {
            runCatching { started.stop() }
            throw FtpServerException(
                "Could not start the server: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
        server = started
        _running.value = config
        _lastError.value = null
    }

    @Synchronized
    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        _running.value = null
    }

    /**
     * Whether anything can bind [port] right now.
     *
     * Opened and closed immediately, which leaves a gap where something else
     * could take it - but the alternative is no warning at all, and the real
     * bind failure is still caught below.
     */
    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (e: IOException) {
        false
    }

    private companion object {
        const val IDLE_SECONDS = 300
        const val MAX_LOGINS = 10
        const val MAX_LOGIN_FAILURES = 3
        const val LOGIN_FAILURE_DELAY_MS = 2_000
    }
}

/** A server failure with something the user can act on. */
class FtpServerException(message: String, cause: Throwable? = null) : Exception(message, cause)
