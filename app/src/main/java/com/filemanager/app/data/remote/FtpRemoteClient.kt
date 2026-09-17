package com.filemanager.app.data.remote

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File

/**
 * FTP, and FTPS when the server is marked secure.
 *
 * Passive mode always. Active mode asks the server to open a connection back
 * to the phone, which no phone on a normal network can accept.
 */
internal class FtpRemoteClient(server: RemoteServer) : RemoteClient {

    private val client: FTPClient = if (server.secure) FTPSClient() else FTPClient()

    init {
        try {
            // Set before connecting: the encoding applies to the control
            // channel, which carries the file names, and autodetect asks the
            // server whether it speaks UTF-8 rather than assuming Latin-1.
            client.controlEncoding = "UTF-8"
            client.autodetectUTF8 = true
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.connect(server.host, server.port)

            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                val code = client.replyCode
                disconnectQuietly()
                throw RemoteException("The server refused the connection (code $code)")
            }

            val loggedIn = if (server.anonymous) {
                client.login("anonymous", "anonymous@")
            } else {
                client.login(server.username, server.password)
            }
            if (!loggedIn) {
                disconnectQuietly()
                throw RemoteException("The user name or password was not accepted")
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            // Applies once data is flowing; the connect timeout above does not.
            client.soTimeout = READ_TIMEOUT_MS
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            disconnectQuietly()
            throw RemoteException(e.message ?: "Could not reach the server", e)
        }
    }

    override fun list(path: String): List<RemoteEntry> = wrap("list this folder") {
        val dir = RemotePaths.normalise(path)
        client.listFiles(dir)
            // A symlink's target may be anywhere, and following one over FTP
            // means another round trip per entry to find out what it is.
            .filter { it.name != "." && it.name != ".." && !it.isSymbolicLink }
            .map { file ->
                RemoteEntry(
                    name = file.name,
                    path = RemotePaths.join(dir, file.name),
                    size = if (file.isDirectory) 0 else file.size,
                    isDir = file.isDirectory,
                    modifiedMs = file.timestamp?.timeInMillis ?: 0L,
                )
            }
    }

    override fun download(path: String, to: File) = wrap("download this file") {
        to.outputStream().use { out ->
            if (!client.retrieveFile(RemotePaths.normalise(path), out)) {
                throw RemoteException("The server would not send the file (${client.replyString.trim()})")
            }
        }
    }

    override fun upload(from: File, path: String) = wrap("upload this file") {
        from.inputStream().use { input ->
            if (!client.storeFile(RemotePaths.normalise(path), input)) {
                throw RemoteException("The server would not accept the file (${client.replyString.trim()})")
            }
        }
    }

    override fun delete(path: String, isDir: Boolean) = wrap("delete this") {
        val target = RemotePaths.normalise(path)
        val done = if (isDir) client.removeDirectory(target) else client.deleteFile(target)
        if (!done) throw RemoteException(client.replyString.trim().ifEmpty { "The server refused" })
    }

    override fun makeDirectory(path: String) = wrap("create this folder") {
        if (!client.makeDirectory(RemotePaths.normalise(path))) {
            throw RemoteException(client.replyString.trim().ifEmpty { "The server refused" })
        }
    }

    override fun rename(from: String, to: String) = wrap("rename this") {
        if (!client.rename(RemotePaths.normalise(from), RemotePaths.normalise(to))) {
            throw RemoteException(client.replyString.trim().ifEmpty { "The server refused" })
        }
    }

    override fun isUsable(): Boolean = client.isConnected && runCatching {
        client.sendNoOp()
    }.getOrDefault(false)

    override fun close() {
        runCatching { client.logout() }
        disconnectQuietly()
    }

    private fun disconnectQuietly() {
        runCatching { if (client.isConnected) client.disconnect() }
    }

    /**
     * Turn whatever the library threw into something with a subject.
     *
     * commons-net reports most failures as a bare IOException whose message is
     * the socket's, so "Connection reset" on its own would be all the user got.
     */
    private inline fun <T> wrap(what: String, body: () -> T): T = try {
        body()
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw RemoteException("Could not $what: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
