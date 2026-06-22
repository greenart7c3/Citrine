package com.greenart7c3.citrine.utils

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    addr is Inet4Address &&
                    addr.isSiteLocalAddress
            }
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(Citrine.TAG, "Failed to determine local IP address", e)
        null
    }
}
