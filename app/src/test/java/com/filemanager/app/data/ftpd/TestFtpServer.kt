package com.filemanager.app.data.ftpd

import com.filemanager.app.data.remote.RemoteServer
import com.filemanager.app.data.remote.RemoteType
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.ServerSocket

/**
 * An FTP server for tests, serving [root] to tester / s3cret.
 *
 * [bytesPerSecond] slows its transfers, so that one can be watched, or stopped,
 * partway - on the loopback a file otherwise arrives before anything can look
 * at it. [activity] puts its file system through ServerActivity, as the app's
 * own server does.
 */
class TestFtpServer(
    root: File,
    bytesPerSecond: Int = 0,
    writable: Boolean = true,
    activity: ServerActivity? = null,
) : AutoCloseable {

    val port: Int = ServerSocket(0).use { it.localPort }

    /** The server as the app would save it. */
    val remote: RemoteServer
        get() = RemoteServer(
            type = RemoteType.FTP,
            host = "127.0.0.1",
            port = port,
            username = "tester",
            password = "s3cret",
        )

    private val server: FtpServer

    init {
        val account = BaseUser().apply {
            name = "tester"
            password = "s3cret"
            homeDirectory = root.absolutePath
            setEnabled(true)
            authorities = buildList<Authority> {
                // Without it every login is refused; see FtpRoundTripTest.
                add(ConcurrentLoginPermission(20, 20))
                if (writable) add(WritePermission())
                if (bytesPerSecond > 0) add(TransferRatePermission(bytesPerSecond, bytesPerSecond))
            }
        }
        val users = object : UserManager {
            override fun getUserByName(name: String?): User? = account.takeIf { name == account.name }
            override fun getAllUserNames(): Array<String> = arrayOf(account.name)
            override fun delete(name: String?) = Unit
            override fun save(user: User?) = Unit
            override fun doesExist(name: String?): Boolean = name == account.name
            override fun getAdminName(): String = account.name
            override fun isAdmin(name: String?): Boolean = false
            override fun authenticate(authentication: Authentication?): User {
                val login = authentication as? UsernamePasswordAuthentication
                if (login?.username == account.name && login.password == account.password) return account
                throw AuthenticationFailedException("Wrong user name or password")
            }
        }

        val native = NativeFileSystemFactory().apply { isCreateHome = false }
        server = FtpServerFactory().apply {
            val listener = ListenerFactory().apply { port = this@TestFtpServer.port }
            addListener("default", listener.createListener())
            userManager = users
            fileSystem = activity?.watch(native) ?: native
            connectionConfig = ConnectionConfigFactory().apply { maxLogins = 20 }.createConnectionConfig()
        }.createServer()
        server.start()
    }

    override fun close() {
        server.stop()
    }
}
