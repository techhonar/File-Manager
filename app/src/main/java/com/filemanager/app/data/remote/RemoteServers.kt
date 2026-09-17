package com.filemanager.app.data.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The saved network locations.
 *
 * One JSON array in the app's own preferences. A database would be the right
 * answer for a list that could grow without bound; this one is however many
 * servers a person has, read once at startup and written when the form is
 * submitted.
 *
 * See [RemoteServer] on what protects the passwords in here, which is worth
 * reading before deciding what to put in it.
 */
class RemoteServers(context: Context) {

    private val prefs = context.getSharedPreferences("remote_servers", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(read())
    val servers: StateFlow<List<RemoteServer>> = _servers.asStateFlow()

    fun byId(id: String): RemoteServer? = _servers.value.firstOrNull { it.id == id }

    /** Adds a new server, or replaces the one with the same id. */
    fun save(server: RemoteServer) {
        val next = _servers.value.toMutableList()
        val at = next.indexOfFirst { it.id == server.id }
        if (at >= 0) next[at] = server else next.add(server)
        write(next)
    }

    fun remove(id: String) {
        write(_servers.value.filterNot { it.id == id })
    }

    private fun write(servers: List<RemoteServer>) {
        val array = JSONArray()
        servers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
        _servers.value = servers
    }

    private fun read(): List<RemoteServer> {
        val stored = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun RemoteServer.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("share", share)
        put("basePath", basePath)
        put("anonymous", anonymous)
        put("secure", secure)
    }

    /**
     * Reads one entry back, tolerating anything missing.
     *
     * A stored server that cannot be read is dropped by the caller rather than
     * taking the whole list with it - losing one entry to a field added in a
     * later version is recoverable, losing all of them is not.
     */
    private fun fromJson(json: JSONObject): RemoteServer {
        val type = runCatching { RemoteType.valueOf(json.getString("type")) }
            .getOrDefault(RemoteType.FTP)
        return RemoteServer(
            id = json.getString("id"),
            name = json.optString("name"),
            type = type,
            host = json.optString("host"),
            port = json.optInt("port", type.defaultPort),
            username = json.optString("username"),
            password = json.optString("password"),
            share = json.optString("share"),
            basePath = json.optString("basePath", "/").ifBlank { "/" },
            anonymous = json.optBoolean("anonymous", false),
            secure = json.optBoolean("secure", false),
        )
    }

    private companion object {
        const val KEY_SERVERS = "servers"
    }
}
