package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.server.Settings
import io.matthewnelson.kmp.file.readUtf8
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.restartDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorListeners
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.TorState
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.service.TorServiceConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TorManager {
    const val ONION_VIRTUAL_PORT: Int = 80

    private const val HS_DIR_NAME = "citrine_hs"

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

    @Volatile
    private var runtime: TorRuntime? = null

    @Volatile
    private var lastLocalPort: Int = 0

    @Volatile
    private var lastEnableHiddenService: Boolean = false

    // Chains start/stop daemon actions so a restart cycle (service onDestroy ->
    // onCreate) can never enqueue StartDaemon while StopDaemon is still executing,
    // which kmp-tor would resolve by interrupting the start.
    @Volatile
    private var pendingAction: Job? = null

    // kmp-tor caches TorRuntime instances per work directory for the life of the
    // process: builder/observer blocks passed to a second TorRuntime.Builder()
    // call for the same environment are silently discarded. Nothing in here may
    // capture per-start parameters — observers and config callbacks must read the
    // volatile fields above. Config callbacks re-run on every daemon (re)start,
    // which is what lets the single cached runtime pick up settings changes.
    private fun buildRuntime(): TorRuntime {
        // Run TorService as a background service (no notification of its own). The
        // relay's own foreground service keeps the process alive, so Tor must not
        // self-destroy on task removal — its lifecycle is driven by start()/stop().
        val serviceConfig = TorServiceConfig.Builder {
            stopServiceOnTaskRemoved = false
        }
        val env = serviceConfig.newEnvironment(ResourceLoaderTorExec::getOrCreate)

        return TorRuntime.Builder(env) {
            val exec = OnEvent.Executor.Immediate

            observerStatic(RuntimeEvent.STATE, exec) { s: TorState ->
                when (val daemon = s.daemon) {
                    is TorState.Daemon.Off -> {
                        if (_state.value !is State.Running) _state.value = State.Off
                    }
                    is TorState.Daemon.Starting,
                    is TorState.Daemon.Stopping,
                    -> _state.value = State.Bootstrapping
                    is TorState.Daemon.On -> {
                        if (!daemon.isBootstrapped) _state.value = State.Bootstrapping
                    }
                }
            }

            observerStatic(RuntimeEvent.ERROR, exec) { t: Throwable ->
                Log.e(Citrine.TAG, "Tor error", t)
                _state.value = State.Error(t.message ?: t::class.simpleName.orEmpty())
            }

            observerStatic(RuntimeEvent.LISTENERS, exec) { listeners: TorListeners ->
                val port = listeners.socks.firstOrNull()?.port?.value
                if (port != null && _socksPort.value != port) {
                    Log.d(Citrine.TAG, "Tor SOCKS listener on port $port")
                    _socksPort.value = port
                    HttpClientManager.setDefaultProxyOnPort(port)
                } else if (listeners.socks.isEmpty() && _socksPort.value != null) {
                    _socksPort.value = null
                    HttpClientManager.setDefaultProxy(null)
                }
            }

            observerStatic(RuntimeEvent.READY, exec) {
                if (!lastEnableHiddenService) {
                    _state.value = State.Running("")
                    return@observerStatic
                }
                val hostnameFile = env.workDirectory.resolve(HS_DIR_NAME).resolve("hostname")
                runCatching { hostnameFile.readUtf8().trim() }
                    .onSuccess { host ->
                        _state.value = State.Running(host)
                        if (Settings.onionHostname != host) {
                            Settings.onionHostname = host
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
                        }
                    }
                    .onFailure {
                        Log.e(Citrine.TAG, "Failed to read onion hostname", it)
                        _state.value = State.Error("hostname unreadable: ${it.message}")
                    }
            }

            config { _ ->
                TorOption.__SocksPort.configure {
                    auto()
                }
            }

            config { _ ->
                if (!lastEnableHiddenService) return@config
                TorOption.HiddenServiceDir.tryConfigure {
                    directory(env.workDirectory.resolve(HS_DIR_NAME))
                    version(3)
                    port(virtual = ONION_VIRTUAL_PORT.toPort()) {
                        target(port = lastLocalPort.toPort())
                    }
                }
            }
        }
    }

    @Synchronized
    fun start(localPort: Int, enableHiddenService: Boolean) {
        lastLocalPort = localPort
        lastEnableHiddenService = enableHiddenService
        _state.value = State.Bootstrapping
        val rt = runtime ?: try {
            buildRuntime()
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Failed to build TorRuntime", e)
            _state.value = State.Error(e.message ?: "tor build failed")
            return
        }
        runtime = rt
        val previous = pendingAction
        pendingAction = Citrine.instance.applicationScope.launch {
            previous?.join()
            try {
                // Restart (rather than start) so that a daemon left running from a
                // previous configuration regenerates its config; if the daemon is
                // off this is equivalent to a plain start.
                rt.restartDaemonAsync()
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Failed to start Tor daemon", e)
                _state.value = State.Error(e.message ?: "tor start failed")
            }
        }
    }

    fun retry() {
        val port = lastLocalPort.takeIf { it > 0 } ?: Settings.port
        start(port, lastEnableHiddenService || Settings.useTor)
    }

    @Synchronized
    fun stop() {
        val rt = runtime ?: run {
            _state.value = State.Off
            return
        }
        _socksPort.value = null
        HttpClientManager.setDefaultProxy(null)
        val previous = pendingAction
        var job: Job? = null
        job = Citrine.instance.applicationScope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                rt.stopDaemonAsync()
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Failed to stop Tor daemon", e)
            } finally {
                // Skip if a newer start was chained behind this stop, so the Off
                // state doesn't clobber its Bootstrapping/Running state.
                if (pendingAction === job) _state.value = State.Off
            }
        }
        pendingAction = job
        job?.start()
    }
}
