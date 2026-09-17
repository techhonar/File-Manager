package com.filemanager.app.data.ftpd

import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission

/**
 * The one account this server has.
 *
 * The library ships user managers backed by a properties file or a database,
 * both of which would mean writing credentials to a second place and keeping
 * it in step with the settings screen. There is exactly one account here and
 * it is whatever the screen last said, so it is held in memory and built from
 * the config.
 */
internal class SingleUserManager(private val config: FtpServerConfig) : UserManager {

    private val account: BaseUser = BaseUser().apply {
        name = if (config.anonymous) UserManager.ANONYMOUS else config.username
        password = config.password
        homeDirectory = config.rootPath
        setEnabled(true)
        maxIdleTime = IDLE_SECONDS
        // Two separate things, both required.
        //
        // WritePermission is how this library says writable; leaving it out is
        // how it says read-only, and there is no separate flag.
        //
        // ConcurrentLoginPermission is not optional. The library asks the user
        // to authorise each login, and an account whose authorities cannot
        // answer that question is refused - so without this, every connection
        // was rejected with "User logged in too many sessions" while no user
        // was logged in at all.
        authorities = buildList<Authority> {
            add(ConcurrentLoginPermission(MAX_CONCURRENT_LOGINS, MAX_LOGINS_PER_CLIENT))
            if (!config.readOnly) add(WritePermission())
        }
    }

    override fun getUserByName(name: String?): User? =
        if (name != null && matches(name)) account else null

    override fun getAllUserNames(): Array<String> = arrayOf(account.name)

    override fun doesExist(name: String?): Boolean = name != null && matches(name)

    /** Nothing here can be edited over FTP, so both are no-ops rather than
     *  errors - the library calls them during normal shutdown. */
    override fun delete(name: String?) = Unit

    override fun save(user: User?) = Unit

    override fun getAdminName(): String = account.name

    override fun isAdmin(name: String?): Boolean = false

    override fun authenticate(authentication: Authentication?): User = when {
        config.anonymous && authentication is AnonymousAuthentication -> account

        authentication is UsernamePasswordAuthentication -> {
            val nameOk = matches(authentication.username ?: "")
            // Not a constant-time comparison, and it does not need to be: this
            // is a LAN server the user starts deliberately and stops when they
            // are done, not a service exposed to the internet.
            val passwordOk = config.anonymous ||
                authentication.password == config.password
            if (nameOk && passwordOk) {
                account
            } else {
                throw AuthenticationFailedException("Wrong user name or password")
            }
        }

        else -> throw AuthenticationFailedException("Anonymous access is off")
    }

    private fun matches(name: String): Boolean =
        if (config.anonymous) true else name == config.username

    private companion object {
        /** Seconds. Long enough to browse, short enough that a forgotten
         *  client does not hold a connection open all day. */
        const val IDLE_SECONDS = 300

        /** A client opens several at once - one for control and one per
         *  transfer - and a desktop file manager will open more than that. */
        const val MAX_CONCURRENT_LOGINS = 10
        const val MAX_LOGINS_PER_CLIENT = 10
    }
}
