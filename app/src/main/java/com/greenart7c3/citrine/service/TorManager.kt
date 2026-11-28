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
import kotlinx.coroutines.CoroutineScope
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
    private lateinit var appContext: Context
    private lateinit var appScope: CoroutineScope

    /** Hands the Tor manager the application context (read only once it is attached) and the app scope; called once from the Citrine constructor. */
    fun install(appContext: Context, appScope: CoroutineScope) {
        this.appContext = appContext
        this.appScope = appScope
    }
    const val ONION_VIRTUAL_PORT: Int = 80

    private const val BOOTSTRAP_POLL_MS = 500L
    private const val SOCKS_BIND_ATTEMPTS = 5

    // A settings "Apply" restarts WebSocketServerService within ~1s, so keeping
    // the client dormant briefly avoids a 15-30s Tor re-bootstrap on unrelated
    // setting edits while still freeing all Tor background tasks and sockets
    // when the relay is actually stopped.
    @Suppress("NoComments")
    private const val DESTROY_AFTER_STOP_DELAY_MS = 10_000L

    // Arti generates the identity key right after startOnionService, so the
    // hostname normally shows up with the first poll. The deadline only
    // matters if the keystore write fails or the launch silently stalls.
    @Suppress("NoComments")
    private const val ONION_HOSTNAME_TIMEOUT_MS = 90_000L

    sealed interface State {
        data object Off : State
        data object Bootstrapping : State
        data class Running(val hostname: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var startedOrStartingJob: Job? = null

    @Volatile
    private var lastChainedLifecycleJob: Job? = null

    @Volatile
    private var pendingDestroy: Job? = null

    private val socksListenerLock = Any()

    @Volatile
    private var nativeLibraryLoaded = false

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
            startedOrStartingJob?.cancel()
            val previous = lastChainedLifecycleJob
            val newJob = appScope.launch(Dispatchers.IO) {
                previous?.join()
                run()
            }
            startedOrStartingJob = newJob
            lastChainedLifecycleJob = newJob
        }
    }

    /**
     * Rebuilds the Arti client (fresh circuits), as restarting the old tor daemon did; the
     * UI's Retry button is the way out of [State.Error].
     */
    fun retry() {
        val port = lastLocalPort.takeIf { it > 0 } ?: Settings.port
        if (restarting) return
        restarting = true
        appScope.launch(Dispatchers.IO) {
            try {
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
            startedOrStartingJob?.cancel()
            startedOrStartingJob = null
            _state.value = State.Off
            releaseSocksPort()
            if (!nativeLibraryLoaded) return
            previous = lastChainedLifecycleJob
            stopJob = appScope.launch(Dispatchers.IO) {
                previous?.join()
                try {
                    synchronized(socksListenerLock) { ArtiNative.stopSocksProxy() }
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
            lastChainedLifecycleJob = stopJob
            if (!destroyClient) {
                pendingDestroy = appScope.launch(Dispatchers.IO) {
                    val self = coroutineContext[Job]
                    delay(DESTROY_AFTER_STOP_DELAY_MS)
                    stopJob.join()
                    synchronized(this@TorManager) {
                        val startOrStopArrivedWhileWaiting = !isActive || startedOrStartingJob != null || pendingDestroy !== self
                        if (startOrStopArrivedWhileWaiting) return@launch
                        pendingDestroy = null
                        val prev = lastChainedLifecycleJob
                        lastChainedLifecycleJob = appScope.launch(Dispatchers.IO) {
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

    @Suppress("NoComments")
    private suspend fun run() {
        try {
            startTor()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable: a missing/broken native library surfaces as an Error
            // (UnsatisfiedLinkError, ExceptionInInitializerError).
            Log.e(Citrine.TAG, "Failed to start built-in Tor", e)
            fail(e.message)
        }
    }

    @Suppress("NoComments")
    private suspend fun startTor() {
        if (!initializeArti()) return

        val port = bindSocksProxy() ?: return
        Log.i(Citrine.TAG, "Built-in Tor SOCKS proxy on port $port")

        if (!startOrStopOnionService()) return

        // The SOCKS listener accepts connections right away (each one waits
        // for its circuit), but report Running only once Arti can carry
        // traffic — like the old daemon's bootstrap did — and, for the
        // onion service, once the hostname is readable from the keystore.
        publishHostname(awaitHostname())
    }

    private suspend fun initializeArti(): Boolean {
        ArtiNative.setLogCallback { line -> Log.d(Citrine.TAG, line) }
        nativeLibraryLoaded = true
        abortIfStoppedBeforeNativeLibraryLoaded()
        Log.i(Citrine.TAG, "Starting built-in Tor: ${ArtiNative.getVersion()}")

        val dataDir = File(appContext.filesDir, "arti").apply { mkdirs() }
        val legacyHsDir = kmpTorHiddenServiceDirHoldingOnionIdentityKey()
        val result = ArtiNative.initialize(
            dataDir.absolutePath,
            legacyHsDir?.absolutePath ?: "",
        )
        if (result != 0) {
            fail("Arti failed to initialize ($result)")
            return false
        }
        currentCoroutineContext().ensureActive()
        wakeFromDormancyLeftByPreviousStop()
        if (legacyHsDir == null) {
            deleteLegacyKmpTorDataWithoutOnionIdentity()
        } else {
            deleteKeylessKmpTorCacheLeavingIdentityDirToArtiImport()
        }
        return true
    }

    private suspend fun abortIfStoppedBeforeNativeLibraryLoaded() {
        currentCoroutineContext().ensureActive()
    }

    private fun kmpTorHiddenServiceDirHoldingOnionIdentityKey(): File? = File(
        appContext.getDir("torservice", Context.MODE_PRIVATE),
        "citrine_hs",
    ).takeIf { File(it, "hs_ed25519_secret_key").exists() }

    private fun wakeFromDormancyLeftByPreviousStop() {
        ArtiNative.setDormant(0)
    }

    private fun deleteKeylessKmpTorCacheLeavingIdentityDirToArtiImport() {
        File(appContext.cacheDir, "torservice").deleteRecursively()
    }

    private fun startOrStopOnionService(): Boolean {
        if (!lastEnableHiddenService) {
            ArtiNative.stopOnionService()
            return true
        }
        val hsResult = ArtiNative.startOnionService(ONION_VIRTUAL_PORT, lastLocalPort)
        if (hsResult != 0) {
            fail("onion service start failed ($hsResult)")
            return false
        }
        return true
    }

    private suspend fun awaitHostname(): String? = withTimeoutOrNull(ONION_HOSTNAME_TIMEOUT_MS) {
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

    private fun publishHostname(hostname: String?) {
        if (hostname == null) {
            fail("onion hostname unavailable")
            return
        }
        _state.value = State.Running(hostname)
        if (hostname.isNotBlank() && Settings.onionHostname != hostname) {
            Settings.onionHostname = hostname
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
        }
    }

    private suspend fun bindSocksProxy(): Int? {
        val startContext = currentCoroutineContext()
        repeat(SOCKS_BIND_ATTEMPTS) {
            val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
            val (username, password) = TorSocksAuthenticator.newCredentials()
            val result = synchronized(socksListenerLock) {
                val stoppedWhileInitializeBlocked = !startContext.isActive
                if (stoppedWhileInitializeBlocked) return null
                ArtiNative.startSocksProxy(port, username, password)
            }
            if (result == 0) {
                publishCredentialsBeforeProxyPort(port, username, password)
                return port
            }
            Log.e(Citrine.TAG, "Failed to bind the Tor SOCKS proxy on port $port ($result)")
        }
        fail("Could not open the Tor SOCKS port")
        return null
    }

    private fun publishCredentialsBeforeProxyPort(port: Int, username: String, password: String) {
        TorSocksAuthenticator.set(port, username, password)
        HttpClientManager.setDefaultProxyOnPort(port)
    }

    /**
     * Drops the SOCKS port and, while OkHttp still dials through it, replaces
     * the proxy with a fail-closed placeholder. Without this, the released
     * loopback port stays configured until Tor rebinds, and a co-installed app
     * that binds it first receives Citrine's proxied dials.
     */
    @Suppress("NoComments")
    private fun releaseSocksPort() {
        TorSocksAuthenticator.clear()
        HttpClientManager.setDefaultProxy(null)
    }

    private fun fail(message: String?) {
        _state.value = State.Error(message ?: "tor error")
        releaseSocksPort()
    }

    private fun deleteLegacyKmpTorDataWithoutOnionIdentity() {
        listOf(
            appContext.getDir("torservice", Context.MODE_PRIVATE),
            File(appContext.cacheDir, "torservice"),
            File(appContext.filesDir, "kmptor"),
            File(appContext.cacheDir, "kmptor"),
        ).forEach { dir ->
            if (dir.exists()) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }
}
