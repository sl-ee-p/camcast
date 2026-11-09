package com.slp.camcast.net

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalAddress {
    /** The phone's LAN IPv4 address, or null when it is not on a network. */
    fun find(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: java.net.SocketException) {
        null
    }
}
