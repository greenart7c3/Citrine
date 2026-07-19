package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes the relay over the FIPS (fips.network) mesh.
 *
 * FIPS is a mesh network that uses Nostr keypairs as node identities; every
 * node gets an IPv6 unique-local address in fd00::/8 derived from its npub.
 * Two modes, chosen by [com.greenart7c3.citrine.server.Settings]:
 *
 * - Embedded node ([startEmbedded]): runs a FIPS node inside Citrine via the
 *   Rust bridge ([FipsNative]) with [FipsVpnService] owning the TUN — the
 *   Tor-style integration, no other apps needed.
 * - External interface ([refresh]): a FIPS node hosted by another app (e.g.
 *   Nostr VPN) already provides a TUN; Citrine just finds its fd00::/8
 *   address and binds the relay to it.
 *
 * The relay watches [state] and (re)binds a connector whenever a Running
 * address appears.
 */
object FipsManager {
    sealed class State {
        data object Off : State()
        data object Starting : State()
        data class Running(val address: String, val npub: String? = null) : State()
        data object NotFound : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    @Volatile
    private var embeddedActive = false

    /** Whether this build bundles the native embedded-node library. */
    val embeddedNodeAvailable: Boolean
        get() = FipsNative.isAvailable

    /**
     * The consent intent the user must approve before [startEmbedded] can
     * establish the TUN, or null when consent was already granted.
     */
    fun prepareEmbedded(context: Context): Intent? = VpnService.prepare(context)

    /**
     * Starts the embedded FIPS node. The node binds asynchronously; the
     * resulting address is published as [State.Running] by [FipsVpnService].
     */
    fun startEmbedded(context: Context) {
        if (!FipsNative.isAvailable) {
            _state.value = State.Error(
                "embedded FIPS node not available in this build",
            )
            return
        }
        if (VpnService.prepare(context) != null) {
            _state.value = State.Error("VPN permission required for the embedded FIPS node")
            return
        }
        if (embeddedActive) return
        embeddedActive = true
        _state.value = State.Starting
        context.startService(
            Intent(context, FipsVpnService::class.java).setAction(FipsVpnService.ACTION_CONNECT),
        )
    }

    fun stopEmbedded(context: Context) {
        if (!embeddedActive) return
        embeddedActive = false
        context.startService(
            Intent(context, FipsVpnService::class.java).setAction(FipsVpnService.ACTION_DISCONNECT),
        )
    }

    internal fun publishRunning(address: String, npub: String?) {
        _state.value = State.Running(address, npub)
    }

    internal fun publishError(message: String) {
        embeddedActive = false
        _state.value = State.Error(message)
    }

    internal fun publishOff() {
        embeddedActive = false
        _state.value = State.Off
    }

    /**
     * Returns an external FIPS interface's fd00::/8 address, or null if none
     * is present. Interfaces named "fips*" are preferred; any "tun*"
     * interface is accepted as a fallback because Android VpnService TUNs are
     * named tun0/tun1 regardless of the app that owns them.
     */
    fun detectAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && (it.name.startsWith("fips") || it.name.startsWith("tun")) }
            .sortedBy { if (it.name.startsWith("fips")) 0 else 1 }
            .firstNotNullOfOrNull { pickUlaAddress(it.inetAddresses.toList()) }
    } catch (e: Exception) {
        Log.w(Citrine.TAG, "Failed to detect FIPS interface", e)
        null
    }

    /**
     * First fd00::/8 unicast address in [addresses], zone id stripped, or null.
     * FIPS derives node addresses from the fd00::/8 ULA range, so the first raw
     * address byte must be 0xfd.
     */
    internal fun pickUlaAddress(addresses: List<InetAddress>): String? = addresses
        .firstOrNull { addr ->
            addr is Inet6Address &&
                !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                (addr.address[0].toInt() and 0xff) == 0xfd
        }
        ?.hostAddress
        ?.substringBefore('%')

    /**
     * External-interface mode: detects the FIPS address and publishes
     * Running/NotFound. Called at relay start; the interface is not
     * re-checked while the relay runs — re-applying the network settings
     * restarts the relay and re-detects.
     */
    fun refresh(): String? {
        val address = detectAddress()
        _state.value = if (address != null) State.Running(address) else State.NotFound
        return address
    }

    fun clear() {
        _state.value = State.Off
    }
}
