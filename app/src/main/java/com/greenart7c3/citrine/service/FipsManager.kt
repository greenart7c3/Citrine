package com.greenart7c3.citrine.service

import android.content.Context
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Exposes the relay over the FIPS (fips.network) mesh.
 *
 * FIPS is a mesh network that uses Nostr keypairs as node identities; every
 * node gets an IPv6 unique-local address in fd00::/8 derived from its npub.
 * Two modes, chosen by [com.greenart7c3.citrine.server.Settings]:
 *
 * - Embedded node ([startEmbedded]): runs a FIPS node inside Citrine via the
 *   Rust bridge ([FipsNative]), Tor-style — mesh TCP is terminated in
 *   userspace by the bridge and proxied to the relay's loopback port. No VPN,
 *   no TUN, no other apps needed.
 * - External interface ([refresh]): a FIPS node hosted by another app (e.g.
 *   Nostr VPN) provides a TUN; Citrine finds its fd00::/8 address and binds
 *   the relay to it directly.
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

    // Serializes nativeStart/nativeStop across the relay service's overlapping
    // stop (onDestroy) and start (fresh onCreate) coroutines.
    private val nativeLock = Any()

    /** Whether this build bundles the native embedded-node library. */
    val embeddedNodeAvailable: Boolean
        get() = FipsNative.isAvailable

    /**
     * Starts the embedded FIPS node for the current [Settings.port]. Runs on a
     * background dispatcher; the resulting address is published as
     * [State.Running].
     */
    fun startEmbedded(context: Context) {
        if (!FipsNative.isAvailable) {
            _state.value = State.Error("embedded FIPS node not available in this build")
            return
        }
        if (embeddedActive) return
        embeddedActive = true
        _state.value = State.Starting
        val appContext = context.applicationContext
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            synchronized(nativeLock) {
                // Idempotent; guarantees a clean slate if a previous node is
                // still winding down from a service restart.
                FipsNative.nativeStop()

                val secret = Settings.fipsSecretKey.ifBlank {
                    val generated = KeyPair().privKey?.toHexKey()
                    if (generated == null) {
                        publishError("failed to generate FIPS identity key")
                        return@launch
                    }
                    Settings.fipsSecretKey = generated
                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
                    generated
                }

                val result = try {
                    JSONObject(FipsNative.nativeStart(secret, Settings.port))
                } catch (e: Exception) {
                    publishError("FIPS node returned invalid state: ${e.message}")
                    return@launch
                }
                val error = result.optString("error")
                if (error.isNotBlank()) {
                    publishError(error)
                    return@launch
                }
                val address = result.optString("address")
                val npub = result.optString("npub")
                if (address.isBlank()) {
                    FipsNative.nativeStop()
                    publishError("FIPS node reported no address")
                    return@launch
                }
                Log.d(Citrine.TAG, "Embedded FIPS node running at $address ($npub)")
                publishRunning(address, npub.takeIf { it.isNotBlank() })
            }
        }
    }

    fun stopEmbedded() {
        if (!embeddedActive) return
        embeddedActive = false
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            synchronized(nativeLock) {
                try {
                    FipsNative.nativeStop()
                } catch (e: Exception) {
                    Log.e(Citrine.TAG, "Error stopping FIPS node", e)
                }
            }
            publishOff()
        }
    }

    private fun publishRunning(address: String, npub: String?) {
        _state.value = State.Running(address, npub)
    }

    private fun publishError(message: String) {
        Log.e(Citrine.TAG, "Embedded FIPS node failed to start: $message")
        embeddedActive = false
        _state.value = State.Error(message)
    }

    private fun publishOff() {
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
