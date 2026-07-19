package com.greenart7c3.citrine.service

import android.content.Intent
import android.net.VpnService
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * VpnService hosting the embedded FIPS node's TUN device.
 *
 * The Rust bridge ([FipsNative]) runs the mesh node; this service only owns
 * the TUN: it routes fd00::/8 into the node and assigns the node's
 * npub-derived address, which makes the relay reachable from other FIPS
 * peers once it binds that address.
 */
class FipsVpnService : VpnService() {
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = when (intent?.action) {
        ACTION_DISCONNECT -> {
            stopNode()
            stopSelf()
            START_NOT_STICKY
        }
        else -> {
            Citrine.instance.applicationScope.launch(Dispatchers.IO) { startNode() }
            START_STICKY
        }
    }

    private fun startNode() {
        if (!running.compareAndSet(false, true)) return
        if (!FipsNative.isAvailable) {
            failStart("embedded FIPS node not available in this build")
            return
        }

        val secret = Settings.fipsSecretKey.ifBlank {
            val generated = KeyPair().privKey?.toHexKey()
            if (generated == null) {
                failStart("failed to generate FIPS identity key")
                return
            }
            Settings.fipsSecretKey = generated
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, this)
            generated
        }

        val result = try {
            JSONObject(FipsNative.nativeStart(secret))
        } catch (e: Exception) {
            failStart("FIPS node returned invalid state: ${e.message}")
            return
        }
        val error = result.optString("error")
        if (error.isNotBlank()) {
            failStart(error)
            return
        }
        val address = result.optString("address")
        val npub = result.optString("npub")
        if (address.isBlank()) {
            stopNativeQuietly()
            failStart("FIPS node reported no address")
            return
        }

        val tun = try {
            Builder()
                .setSession("Citrine FIPS")
                .addAddress(address, 128)
                .addRoute(FIPS_ROUTE, FIPS_ROUTE_PREFIX)
                .setMtu(FIPS_MTU)
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            null.also { Log.e(Citrine.TAG, "Failed to establish FIPS TUN", e) }
        }
        if (tun == null) {
            stopNativeQuietly()
            failStart("could not establish FIPS TUN (VPN permission revoked?)")
            return
        }

        if (!FipsNative.nativeAttachTunFd(tun.detachFd())) {
            stopNativeQuietly()
            failStart("could not attach FIPS TUN to the node")
            return
        }

        Log.d(Citrine.TAG, "Embedded FIPS node running at $address ($npub)")
        FipsManager.publishRunning(address, npub.takeIf { it.isNotBlank() })
    }

    private fun failStart(message: String) {
        Log.e(Citrine.TAG, "Embedded FIPS node failed to start: $message")
        running.set(false)
        FipsManager.publishError(message)
        stopSelf()
    }

    private fun stopNativeQuietly() {
        try {
            FipsNative.nativeStop()
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Error stopping FIPS node", e)
        }
    }

    private fun stopNode() {
        if (!running.getAndSet(false)) return
        stopNativeQuietly()
        FipsManager.publishOff()
    }

    override fun onDestroy() {
        stopNode()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopNode()
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val ACTION_CONNECT = "com.greenart7c3.citrine.fips.CONNECT"
        const val ACTION_DISCONNECT = "com.greenart7c3.citrine.fips.DISCONNECT"

        // FIPS derives node addresses from fd00::/8 and defaults its UDP
        // transport MTU to 1280 (IPv6 minimum).
        private const val FIPS_ROUTE = "fd00::"
        private const val FIPS_ROUTE_PREFIX = 8
        private const val FIPS_MTU = 1280
    }
}
