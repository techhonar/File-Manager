package com.filemanager.app.data.remote

/**
 * Keeps live connections to each server, one per operation in flight.
 *
 * Every protocol here except WebDAV pays for a handshake - a TCP round trip,
 * a login, and for SFTP and SMB a key exchange - so opening one per directory
 * listing would make browsing feel like it was working through treacle.
 *
 * A connection is lent out for the length of one operation and taken back
 * afterwards, never shared. The remote browser lets the user keep browsing
 * while a transfer runs, and an FTP connection carries one conversation at a
 * time: a listing sent down the connection a download was using took the
 * download's reply as its own, and the download failed. Worse, the health
 * check below ran on that same connection mid-transfer and, finding it
 * confused, closed it under the download. A second operation now gets a
 * second connection, and the spare is kept for whatever comes next.
 *
 * Only the bookkeeping is synchronised. Opening a connection and checking one
 * both wait on the network, and holding the lock through either stalled every
 * other server behind one slow one.
 */
class RemoteConnections(
    private val open: (RemoteServer) -> RemoteClient = ::openClient,
) {

    /** Connections not in use, by server id. */
    private val idle = mutableMapOf<String, ArrayDeque<RemoteClient>>()

    /**
     * Bumped when a server's settings change. A connection lent out before
     * that is closed on return rather than kept, since it was opened with
     * settings that no longer apply.
     */
    private val generations = mutableMapOf<String, Int>()

    /**
     * Runs [body] with a connection to [server] that nothing else is using.
     *
     * A dropped idle connection is the normal case rather than an error: FTP
     * servers in particular cut them after a minute or two, and the user has
     * no reason to care as long as the next thing they tap still works. So
     * a held one is checked before it is handed over and replaced if dead.
     */
    fun <T> use(server: RemoteServer, body: (RemoteClient) -> T): T {
        val (client, generation) = lease(server)
        try {
            return body(client)
        } finally {
            giveBack(server.id, client, generation)
        }
    }

    private fun lease(server: RemoteServer): Pair<RemoteClient, Int> {
        while (true) {
            val (held, generation) = synchronized(this) {
                idle[server.id]?.removeFirstOrNull() to generationOf(server.id)
            }
            if (held == null) return open(server) to generation
            if (runCatching { held.isUsable() }.getOrDefault(false)) return held to generation
            runCatching { held.close() }
        }
    }

    private fun giveBack(id: String, client: RemoteClient, generation: Int) {
        val kept = synchronized(this) {
            val spares = idle.getOrPut(id) { ArrayDeque() }
            (generation == generationOf(id) && spares.size < MAX_IDLE)
                .also { if (it) spares.addLast(client) }
        }
        if (!kept) runCatching { client.close() }
    }

    private fun generationOf(id: String): Int = generations[id] ?: 0

    /** Drops the held connections, if any. Used when a server is edited or
     *  deleted, where the old settings must not survive - including in a
     *  connection that is lent out at the time. */
    fun disconnect(id: String) {
        val dropped = synchronized(this) {
            generations[id] = generationOf(id) + 1
            idle.remove(id).orEmpty()
        }
        dropped.forEach { runCatching { it.close() } }
    }

    /**
     * Opens without touching the pool, for the form's Test button.
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

    private companion object {
        /**
         * Spares kept per server. Browsing during a transfer needs two; more
         * would only hold sockets open that the server may count against us.
         */
        const val MAX_IDLE = 2
    }
}

private fun openClient(server: RemoteServer): RemoteClient = when (server.type) {
    RemoteType.FTP -> FtpRemoteClient(server)
    RemoteType.SFTP -> SftpRemoteClient(server)
    RemoteType.SMB -> SmbRemoteClient(server)
    RemoteType.WEBDAV -> WebDavRemoteClient(server)
}
