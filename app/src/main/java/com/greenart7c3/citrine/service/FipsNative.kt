package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log

/**
 * JNI surface of the embedded FIPS node (rust/fips-bridge, libcitrine_fips.so).
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
     * Starts the embedded node with the given nsec/hex secret. Returns JSON:
     * `{"running":true,"npub":"...","address":"fd..","peerCount":0}` or `{"error":"..."}`.
     */
    external fun nativeStart(secretKey: String): String

    /** Hands the VpnService TUN fd to the node (ownership transfers to native code). */
    external fun nativeAttachTunFd(fd: Int): Boolean

    /** Returns `{"running":bool,"npub":...,"address":...,"peerCount":n}`. */
    external fun nativeStateJson(): String

    /** Stops the node and closes the TUN fd. */
    external fun nativeStop()
}
