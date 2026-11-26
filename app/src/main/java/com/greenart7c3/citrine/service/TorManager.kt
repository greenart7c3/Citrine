package com.greenart7c3.citrine.service

import android.content.Context
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.tor.ArtiNative
import com.greenart7c3.citrine.tor.TorSocksAuthenticator
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Built-in Tor, backed by Arti (Tor in Rust) running in-process through
 * [ArtiNative]. Ported from Amber's TorManager; Citrine additionally runs an
 * onion service (the relay's .onion address) instead of only a SOCKS proxy.
 */
object TorManager {
    const val ONION_VIRTUAL_PORT: Int = 80

    private const val BOOTSTRAP_POLL_MS = 500L
    private const val SOCKS_BIND_ATTEMPTS = 5

    // Grace window after stop() before tearing down the TorClient completely.
    // A settings "Apply" restarts WebSocketServerService within ~1s, so keeping
    // the client dormant briefly avoids a 15-30s re-bootstrap on unrelated
    // setting edits while still freeing all Tor background tasks and sockets
    // when the relay is actually stopped.
    private const val DESTROY_AFTER_STOP_DELAY_MS = 10_000L

    // The identity key is generated right after startOnionService, so the
    // hostname normally shows up with the first poll. The deadline only
    // matters if the keystore write fails or the launch silently stalls.
    private const val ONION_HOSTNAME_TIMEOUT_MS = 90_000L

    sealed interface State {
        data object Off : State
        data object Bootstrapping : State
        data class Running(val hostname: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    /** The start → bootstrap job; non-null and active while Tor is up or coming up. */
    @Volatile
    private var job: Job? = null

    /** Pending stop/destroy cleanup job, chained so lifecycle calls never race. */
    @Volatile
    private var pendingLifecycle: Job? = null

    /** Delayed full teardown scheduled by [stop]; cancelled if [start] runs again promptly. */
    @Volatile
    private var pendingDestroy: Job? = null

    /** Serializes binding and releasing the SOCKS listener against [stop]. */
    private val proxyLock = Any()

    /** True once the native library loaded; native calls are skipped until then. */
    @Volatile
    private var nativeReady = false

    @Volatile
    private var lastLocalPort: Int = 0

    @Volatile
    private var lastEnableHiddenService: Boolean = false

    @Volatile
    private var restarting = false

    fun start(localPort: Int, enableHiddenService: Boolean) {
        synchronized(this) {
            lastLocalPort = localPort
            lastEnableHiddenService = enableHiddenService
            _state.value = State.Bootstrapping
            pendingDestroy?.cancel()
            pendingDestroy = null
            job?.cancel()
            val previous = pendingLifecycle
            val newJob = Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                previous?.join()
                run()
            }
            job = newJob
            pendingLifecycle = newJob
        }
    }

    fun retry() {
        val port = lastLocalPort.takeIf { it > 0 } ?: Settings.port
        if (restarting) return
        restarting = true
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            try {
                // Rebuild the client (fresh circuits), as restarting the old tor
                // daemon did — the UI's Retry button is the way out of Error.
                stop(destroyClient = true)
                start(port, lastEnableHiddenService || Settings.useTor)
            } finally {
                restarting = false
            }
        }
    }

    /**
     * Stops the SOCKS listener and onion service and puts the Arti client to sleep so it
     * stops sending channel padding and running periodic background tasks.
     *
     * When [destroyClient] is true (e.g. Tor was disabled in settings, or [retry] wants
     * fresh circuits), the underlying `TorClient` is destroyed immediately; otherwise it is
     * destroyed after [DESTROY_AFTER_STOP_DELAY_MS] unless [start] is called again first
     * (covering quick service restarts on unrelated settings edits).
     */
    fun stop(destroyClient: Boolean = false) {
        val previous: Job?
        val stopJob: Job
        synchronized(this) {
            pendingDestroy?.cancel()
            pendingDestroy = null
            job?.cancel()
            job = null
            _state.value = State.Off
            releaseSocksPort()
            if (!nativeReady) return
            previous = pendingLifecycle
            stopJob = Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                previous?.join()
                try {
                    synchronized(proxyLock) { ArtiNative.stopSocksProxy() }
                    ArtiNative.stopOnionService()
                    if (destroyClient) {
                        ArtiNative.destroy()
                        Log.i(Citrine.TAG, "Built-in Tor stopped and destroyed")
                    } else {
                        ArtiNative.setDormant(1)
                        Log.i(Citrine.TAG, "Built-in Tor stopped (dormant)")
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.e(Citrine.TAG, "Error stopping built-in Tor", e)
                }
            }
            pendingLifecycle = stopJob
            if (!destroyClient) {
                pendingDestroy = Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                    val self = coroutineContext[Job]
                    delay(DESTROY_AFTER_STOP_DELAY_MS)
                    stopJob.join()
                    synchronized(this@TorManager) {
                        // A new start() or stop() arrived while we were waiting.
                        if (!isActive || job != null || pendingDestroy !== self) return@launch
                        pendingDestroy = null
                        val prev = pendingLifecycle
                        pendingLifecycle = Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                            prev?.join()
                            try {
                                ArtiNative.destroy()
                                Log.i(Citrine.TAG, "Built-in Tor client destroyed after idle stop")
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                Log.e(Citrine.TAG, "Error destroying idle built-in Tor client", e)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun run() {
        try {
            ArtiNative.setLogCallback { line -> Log.d(Citrine.TAG, line) }
            nativeReady = true
            // Check cancellation after publishing nativeReady: if stop() ran before
            // nativeReady became true it cancelled our job without queuing a stopJob,
            // so we must bail out before initialize() creates a live TorClient.
            currentCoroutineContext().ensureActive()
            Log.i(Citrine.TAG, "Starting built-in Tor: ${ArtiNative.getVersion()}")

            val dataDir = File(Citrine.instance.filesDir, "arti").apply { mkdirs() }
            // kmp-tor's hidden service directory holds the relay's onion
            // identity key: hand it to Arti for the address carry-over before
            // anything can generate a fresh key. kmp-tor's work directory is
            // the app_torservice getDir.
            val legacyHsDir = File(
                Citrine.instance.getDir("torservice", Context.MODE_PRIVATE),
                "citrine_hs",
            ).takeIf { File(it, "hs_ed25519_secret_key").exists() }
            val result = ArtiNative.initialize(
                dataDir.absolutePath,
                legacyHsDir?.absolutePath ?: "",
            )
            if (result != 0) {
                fail("Arti failed to initialize ($result)")
                return
            }
            currentCoroutineContext().ensureActive()
            // Wake the client if a previous stop() placed it in DormantMode::Soft.
            ArtiNative.setDormant(0)
            if (legacyHsDir == null) {
                // kmp-tor leftovers without a hidden service identity.
                deleteLegacyKmpTorData()
            } else {
                // The identity-bearing directory is the native side's to remove
                // (only after a successful import); the Tor cache holds no
                // keys and can go as soon as Arti initialized.
                File(Citrine.instance.cacheDir, "torservice").deleteRecursively()
            }

            val port = bindSocksProxy() ?: return
            Log.i(Citrine.TAG, "Built-in Tor SOCKS proxy on port $port")

            if (lastEnableHiddenService) {
                val hsResult = ArtiNative.startOnionService(ONION_VIRTUAL_PORT, lastLocalPort)
                if (hsResult != 0) {
                    fail("onion service start failed ($hsResult)")
                    return
                }
            } else {
                ArtiNative.stopOnionService()
            }

            // The SOCKS listener accepts connections right away (each one waits
            // for its circuit), but report Running only once Arti can carry
            // traffic — like the old daemon's bootstrap did — and, for the
            // onion service, once the hostname is readable from the keystore.
            val hostname = withTimeoutOrNull(ONION_HOSTNAME_TIMEOUT_MS) {
                while (currentCoroutineContext().isActive) {
                    if (ArtiNative.isBootstrapped() == 1) {
                        Log.i(Citrine.TAG, "Built-in Tor bootstrapped")
                        if (!lastEnableHiddenService) return@withTimeoutOrNull ""
                        ArtiNative.getOnionHostname().takeIf { it.isNotBlank() }?.let { return@withTimeoutOrNull it }
                    }
                    delay(BOOTSTRAP_POLL_MS.milliseconds)
                }
                null
            }
            if (hostname == null) {
                fail("onion hostname unavailable")
                return
            }
            _state.value = State.Running(hostname)
            if (hostname.isNotBlank() && Settings.onionHostname != hostname) {
                Settings.onionHostname = hostname
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: a missing/broken native library surfaces as an Error
            // (UnsatisfiedLinkError, ExceptionInInitializerError).
            Log.e(Citrine.TAG, "Failed to start built-in Tor", e)
            fail(e.message)
        }
    }

    /** Binds the SOCKS listener on a free loopback port; null (and Error) when none could be bound. */
    private suspend fun bindSocksProxy(): Int? {
        val context = currentCoroutineContext()
        repeat(SOCKS_BIND_ATTEMPTS) {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val (username, password) = TorSocksAuthenticator.newCredentials()
            val result = synchronized(proxyLock) {
                // stop() may have run while initialize() was blocking.
                if (!context.isActive) return null
                ArtiNative.startSocksProxy(port, username, password)
            }
            if (result == 0) {
                // Published before the port, so no dial can reach the listener without them.
                TorSocksAuthenticator.set(port, username, password)
                _socksPort.value = port
                HttpClientManager.setDefaultProxyOnPort(port)
                return port
            }
            Log.e(Citrine.TAG, "Failed to bind the Tor SOCKS proxy on port $port ($result)")
        }
        fail("Could not open the Tor SOCKS port")
        return null
    }

    /**
     * Drops the SOCKS port and, while OkHttp still dials through it, replaces
     * the proxy with a fail-closed placeholder. Without this, the released
     * loopback port stays configured until Tor rebinds, and a co-installed app
     * that binds it first receives Citrine's proxied dials.
     */
    private fun releaseSocksPort() {
        _socksPort.value = null
        TorSocksAuthenticator.clear()
        HttpClientManager.setDefaultProxy(null)
    }

    private fun fail(message: String?) {
        _state.value = State.Error(message ?: "tor error")
        releaseSocksPort()
    }

    /**
     * kmp-tor's working directories from before the switch to Arti (the
     * app_torservice getDir plus its cache). Only called when there is no
     * hidden service identity in them: with one, the native side removes the
     * directory itself after importing the key.
     */
    private fun deleteLegacyKmpTorData() {
        listOf(
            Citrine.instance.getDir("torservice", Context.MODE_PRIVATE),
            File(Citrine.instance.cacheDir, "torservice"),
            File(Citrine.instance.filesDir, "kmptor"),
            File(Citrine.instance.cacheDir, "kmptor"),
        ).forEach { dir ->
            if (dir.exists()) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }
}
