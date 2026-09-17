package com.filemanager.app.data.remote

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.File

/**
 * SFTP - file transfer over SSH, not "FTP with TLS".
 *
 * Host keys are accepted without checking. That is worth being plain about:
 * it means this trusts whatever answers on the address, so a machine that has
 * interposed itself can read the session. Verifying properly needs somewhere
 * to remember a key per host and a way to ask the user about a change, which
 * is a feature in its own right rather than something to half-do here.
 */
internal class SftpRemoteClient(server: RemoteServer) : RemoteClient {

    private val ssh = SSHClient()
    private val sftp: SFTPClient

    init {
        try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = CONNECT_TIMEOUT_MS
            ssh.timeout = READ_TIMEOUT_MS
            ssh.connect(server.host, server.port)
            ssh.authPassword(server.username, server.password)
            sftp = ssh.newSFTPClient()
        } catch (e: Exception) {
            runCatching { ssh.disconnect() }
            throw RemoteException(
                "Could not sign in: ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    override fun list(path: String): List<RemoteEntry> = wrap("list this folder") {
        val dir = RemotePaths.normalise(path)
        sftp.ls(dir)
            .filter { it.name != "." && it.name != ".." }
            .map { info ->
                val attrs = info.attributes
                val isDir = attrs.type == FileMode.Type.DIRECTORY
                RemoteEntry(
                    name = info.name,
                    path = RemotePaths.join(dir, info.name),
                    size = if (isDir) 0 else attrs.size,
                    isDir = isDir,
                    // SFTP reports seconds; everything above this works in
                    // milliseconds, and without the conversion every file
                    // would date to January 1970.
                    modifiedMs = attrs.mtime * 1000L,
                )
            }
    }

    override fun download(path: String, to: File) = wrap("download this file") {
        sftp.get(RemotePaths.normalise(path), to.absolutePath)
    }

    override fun upload(from: File, path: String) = wrap("upload this file") {
        sftp.put(from.absolutePath, RemotePaths.normalise(path))
    }

    override fun delete(path: String, isDir: Boolean) = wrap("delete this") {
        val target = RemotePaths.normalise(path)
        if (isDir) sftp.rmdir(target) else sftp.rm(target)
    }

    override fun makeDirectory(path: String) = wrap("create this folder") {
        sftp.mkdir(RemotePaths.normalise(path))
    }

    override fun rename(from: String, to: String) = wrap("rename this") {
        sftp.rename(RemotePaths.normalise(from), RemotePaths.normalise(to))
    }

    override fun isUsable(): Boolean = ssh.isConnected

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

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
