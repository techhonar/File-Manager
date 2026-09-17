package com.filemanager.app.data.ftpd

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The address another machine on the same network would use to reach here.
 *
 * Read from the interfaces rather than from WifiManager: that one only knows
 * about Wi-Fi, and returns nothing useful when the phone is sharing its own
 * hotspot or is on a wired adapter - both of which are exactly when someone
 * would be running a server on it.
 */
object NetworkAddress {

    /** Best guess at the local IPv4 address, or null when offline. */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            // Wi-Fi and hotspot interfaces before anything else. A phone often
            // has half a dozen up at once - mobile data, various virtual ones -
            // and the mobile one is not reachable from a laptop on the sofa.
            .sortedBy { interfacePriority(it.name) }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun interfacePriority(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("ap") || name.startsWith("swlan") -> 1
        name.startsWith("eth") -> 2
        else -> 3
    }
}
