package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log

/**
 * JNI surface of the embedded FIPS node (rust/fips-bridge, libcitrine_fips.so).
 *
 * The node runs entirely in userspace, Tor-style: a smoltcp stack inside the
 * bridge terminates mesh TCP at the node's fd00::/8 address and proxies the
 * byte stream to the relay's loopback port. No TUN or VpnService involved.
 *
 * The native library is optional: builds made without the Rust toolchain ship
 * without it, in which case [isAvailable] is false and the embedded-node
 * setting falls back to external-interface detection.
 */
object FipsNative {
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("citrine_fips")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(Citrine.TAG, "libcitrine_fips.so not bundled; embedded FIPS node unavailable: ${e.message}")
            false
        }
    }

    /**
     * Starts the embedded node with the given nsec/hex secret. Mesh TCP for
     * [proxyPort] is terminated in userspace and proxied to
     * 127.0.0.1:[proxyPort]. Returns JSON:
     * `{"running":true,"npub":"...","address":"fd..","peerCount":0}` or `{"error":"..."}`.
     */
    external fun nativeStart(secretKey: String, proxyPort: Int): String

    /** Returns `{"running":bool,"npub":...,"address":...,"peerCount":n}`. */
    external fun nativeStateJson(): String

    /** Stops the node. Safe to call when not running. */
    external fun nativeStop()
}
