package com.filemanager.app.data.remote

/**
 * Keeps one live connection per server.
 *
 * Every protocol here except WebDAV pays for a handshake - a TCP round trip,
 * a login, and for SFTP and SMB a key exchange - so opening one per directory
 * listing would make browsing feel like it was working through treacle.
 *
 * Synchronised throughout. Connections are opened from whichever IO thread the
 * repository happens to be on, and two coroutines racing to open the same
 * server would leave one of them orphaned and never closed.
 */
class RemoteConnections {

    private val clients = mutableMapOf<String, RemoteClient>()

    /**
     * A usable client for [server], reconnecting if the held one has died.
     *
     * A dropped idle connection is the normal case rather than an error: FTP
     * servers in particular cut them after a minute or two, and the user has
     * no reason to care as long as the next thing they tap still works.
     */
    @Synchronized
    fun connect(server: RemoteServer): RemoteClient {
        clients[server.id]?.let { held ->
            if (runCatching { held.isUsable() }.getOrDefault(false)) return held
            clients.remove(server.id)
            runCatching { held.close() }
        }

        val client = open(server)
        clients[server.id] = client
        return client
    }

    /** Drops the held connection, if any. Used when a server is edited or
     *  deleted, where the old settings must not survive. */
    @Synchronized
    fun disconnect(id: String) {
        clients.remove(id)?.let { runCatching { it.close() } }
    }

    @Synchronized
    fun closeAll() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }

    /**
     * Opens without touching the cache, for the form's Test button.
     *
     * Deliberately separate: testing a server the user is still editing must
     * not replace the working connection to the saved version of it.
     */
    fun test(server: RemoteServer): Unit = open(server).use { client ->
        // Listing proves rather more than connecting does - a server can
        // accept a login and still refuse every path, which is what a wrong
        // share name or a missing base path looks like.
        client.list(server.basePath)
    }

    private fun open(server: RemoteServer): RemoteClient = when (server.type) {
        RemoteType.FTP -> FtpRemoteClient(server)
        RemoteType.SFTP -> SftpRemoteClient(server)
        RemoteType.SMB -> SmbRemoteClient(server)
        RemoteType.WEBDAV -> WebDavRemoteClient(server)
    }
}
