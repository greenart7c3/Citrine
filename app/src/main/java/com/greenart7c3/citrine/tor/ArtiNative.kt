package com.greenart7c3.citrine.tor

/**
 * JNI bridge to the Arti (Tor in Rust) wrapper built from `tools/arti`
 * (`libcitrine_arti.so`, committed under `app/src/main/jniLibs`).
 * Ported from Amber's ArtiNative, extended with the onion service calls
 * Citrine needs to expose its relay over Tor.
 *
 * The native TorClient is created by [initialize] and kept for the process
 * lifetime; the SOCKS proxy and the onion service start and stop
 * independently of it. [destroy] drops the client so the next [initialize]
 * rebuilds it from scratch.
 */
object ArtiNative {
    init {
        System.loadLibrary("citrine_arti")
    }

    external fun getVersion(): String

    external fun setLogCallback(callback: ArtiLogCallback)

    /**
     * Creates the Tor client and starts the directory download in the
     * background. Returns as soon as the client exists.
     *
     * [legacyHiddenServiceDir] is the kmp-tor-era hidden service directory
     * (empty when absent). A C tor identity key found there is imported into
     * the Arti keystore so the relay's .onion address survives the upgrade;
     * the legacy directory is removed only after a successful import.
     *
     * @return 0 on success, negative on error.
     */
    external fun initialize(dataDir: String, legacyHiddenServiceDir: String): Int

    /** 1 when Tor can carry traffic, 0 while still bootstrapping, -1 without a client. */
    external fun isBootstrapped(): Int

    /** Directory-download progress in per mille (0..1000), or -1 without a client. */
    external fun bootstrapProgressPermille(): Int

    /**
     * Binds the SOCKS5 proxy on 127.0.0.1:[port], replacing any previous listener.
     * Clients must authenticate with [username] / [password] (RFC 1929).
     * @return 0 on success, negative on error (-3: the port could not be bound,
     * -4: empty or over-long credentials).
     */
    external fun startSocksProxy(port: Int, username: String, password: String): Int

    /** Stops the SOCKS5 listener; the Tor client stays alive. */
    external fun stopSocksProxy(): Int

    /**
     * Launches the relay's onion service, forwarding its [virtualPort] to
     * 127.0.0.1:[targetPort] where the WebSocket server listens. The identity
     * key lives in the client keystore, so the .onion address survives
     * restarts; read it with [getOnionHostname]. A running service is torn
     * down first and relaunched with the new target port.
     * @return 0 on success, negative on error.
     */
    external fun startOnionService(virtualPort: Int, targetPort: Int): Int

    /**
     * The relay's .onion address ("<56 chars>.onion"), read from the client
     * keystore; empty while the identity key does not exist yet.
     */
    external fun getOnionHostname(): String

    /** Stops the onion service; the Tor client stays alive. */
    external fun stopOnionService(): Int

    /**
     * Sets Arti's dormant mode: 1 = soft dormant (pauses periodic background tasks and
     * channel padding), 0 = normal active mode.
     * @return 0 on success, -1 when no client exists.
     */
    external fun setDormant(dormant: Int): Int

    /** Drops the Tor client so the next [initialize] starts over (fresh circuits). */
    external fun destroy(): Int
}

/** Receives Arti's log lines from the native layer (called by name from Rust). */
fun interface ArtiLogCallback {
    fun onLogLine(line: String)
}
