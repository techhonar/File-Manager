package com.filemanager.app.data.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2 and SMB3 - Windows file sharing, and what most NAS boxes speak.
 *
 * SMB1 is not supported by the library and is not worth adding: it is off by
 * default on every current Windows and has been the way into more than one
 * worm.
 *
 * Paths here are relative to the share, which is connected once and held. The
 * share is part of the saved server rather than the path because a session can
 * hold several and swapping between them mid-browse is not something this
 * screen offers.
 */
internal class SmbRemoteClient(server: RemoteServer) : RemoteClient {

    private val client: SMBClient
    private val connection: Connection
    private val session: Session
    private val share: DiskShare

    init {
        // Built into locals first, then published to the properties in one go.
        // Four things are opened in sequence here and any of them can fail;
        // without the locals there would be no way to close the ones that did
        // open, since a property assigned in init cannot be tested for having
        // been assigned.
        var newClient: SMBClient? = null
        var newConnection: Connection? = null
        var newSession: Session? = null
        try {
            newClient = SMBClient(
                SmbConfig.builder()
                    .withTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )
            newConnection = newClient.connect(server.host, server.port)

            // "DOMAIN\\user" is how Windows writes it, and how anyone setting
            // this up will have been given it, so accept it rather than asking
            // for a separate field almost nobody needs.
            val domain = server.username.substringBefore('\\', "")
            val user = server.username.substringAfter('\\')
            val auth = if (server.anonymous) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(user, server.password.toCharArray(), domain.ifEmpty { null })
            }

            newSession = newConnection.authenticate(auth)
            val connected = newSession.connectShare(server.share)
            if (connected !is DiskShare) {
                throw RemoteException("\"${server.share}\" is not a file share")
            }

            client = newClient
            connection = newConnection
            session = newSession
            share = connected
        } catch (e: Exception) {
            runCatching { newSession?.close() }
            runCatching { newConnection?.close() }
            runCatching { newClient?.close() }
            throw if (e is RemoteException) {
                e
            } else {
                RemoteException(
                    "Could not open the share: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
        }
    }

    override fun list(path: String): List<RemoteEntry> = wrap("list this folder") {
        val dir = RemotePaths.normalise(path)
        share.list(RemotePaths.toSmb(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = info.fileAttributes and
                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                RemoteEntry(
                    name = info.fileName,
                    path = RemotePaths.join(dir, info.fileName),
                    size = if (isDir) 0 else info.endOfFile,
                    isDir = isDir,
                    modifiedMs = info.lastWriteTime.toEpochMillis(),
                )
            }
    }

    override fun download(path: String, to: File): Unit = wrap("download this file") {
        share.openFile(
            RemotePaths.toSmb(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { remote ->
            remote.inputStream.use { input ->
                to.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    override fun upload(from: File, path: String): Unit = wrap("upload this file") {
        share.openFile(
            RemotePaths.toSmb(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            // Truncates an existing file rather than failing, which is what
            // overwriting means everywhere else in the app.
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null,
        ).use { remote ->
            remote.outputStream.use { output ->
                from.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }

    override fun delete(path: String, isDir: Boolean) = wrap("delete this") {
        val target = RemotePaths.toSmb(path)
        // Recursive: the browser deletes what the user selected, and a folder
        // that refuses to go because something is inside it would be a worse
        // answer than the one they asked for.
        if (isDir) share.rmdir(target, true) else share.rm(target)
    }

    override fun makeDirectory(path: String) = wrap("create this folder") {
        share.mkdir(RemotePaths.toSmb(path))
    }

    override fun rename(from: String, to: String) = wrap("rename this") {
        share.openFile(
            RemotePaths.toSmb(from),
            EnumSet.of(AccessMask.GENERIC_ALL),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { it.rename(RemotePaths.toSmb(to)) }
    }

    override fun isUsable(): Boolean = runCatching {
        connection.isConnected && share.isConnected
    }.getOrDefault(false)

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private inline fun <T> wrap(what: String, body: () -> T): T = try {
        body()
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException("Could not $what: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 30L
        const val SOCKET_TIMEOUT_SECONDS = 60L
    }
}
