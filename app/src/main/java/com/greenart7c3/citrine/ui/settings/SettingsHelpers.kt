package com.greenart7c3.citrine.ui.settings

import android.net.InetAddresses.isNumericAddress
import android.os.Build
import android.util.Patterns
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.utils.Hex

fun String.toShortenHex(): String {
    if (length <= 16) return this
    return "${take(8)}...${takeLast(8)}"
}

fun isIpValid(ip: String): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    isNumericAddress(ip)
} else {
    @Suppress("DEPRECATION")
    Patterns.IP_ADDRESS.matcher(ip).matches()
}

fun normalizeRelayInput(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        "://" in trimmed -> return null
        else -> "wss://$trimmed"
    }
    val afterScheme = withScheme.substringAfter("://")
    if (afterScheme.isEmpty() || afterScheme.startsWith("/")) return null
    return withScheme
}

fun String.toNostrKey(): String? {
    val key = try {
        Hex.decode(this)
    } catch (_: Exception) {
        null
    }
    if (key != null) {
        return this
    }

    val pubKeyParsed =
        when (val parsed = Nip19Parser.uriToRoute(this)?.entity) {
            is NPub -> parsed.hex.hexToByteArray()
            is NProfile -> parsed.hex.hexToByteArray()
            else -> null
        }
    return pubKeyParsed?.toHexKey()
}
