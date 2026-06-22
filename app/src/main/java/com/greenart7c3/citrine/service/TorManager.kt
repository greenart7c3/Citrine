package com.greenart7c3.citrine.service

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.server.Settings
import io.matthewnelson.kmp.file.readUtf8
import io.matthewnelson.kmp.file.resolve
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorListeners
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.TorState
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.service.TorServiceConfig
import io.matthewnelson.kmp.tor.runtime.service.TorServiceUI
import io.matthewnelson.kmp.tor.runtime.service.ui.KmpTorServiceUI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object TorManager {
    const val ONION_VIRTUAL_PORT: Int = 80

    private const val HS_DIR_NAME = "citrine_hs"
    private const val NOTIFICATION_ID: Short = 615

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

    private fun buildRuntime(localPort: Int, enableHiddenService: Boolean): TorRuntime {
        val uiFactory = KmpTorServiceUI.Factory(
            iconReady = R.drawable.ic_notification,
            iconNotReady = R.drawable.ic_notification,
            info = TorServiceUI.NotificationInfo(
                notificationId = NOTIFICATION_ID,
                channelId = "citrine_tor",
                channelName = R.string.tor_channel_name,
                channelDescription = R.string.tor_channel_description,
                channelShowBadge = false,
                channelImportanceLow = true,
            ),
            block = { defaultConfig { enableActionStop = true } },
        )

        val serviceConfig = TorServiceConfig.Foreground.Builder(uiFactory) { /* defaults */ }
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
                if (!enableHiddenService) {
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

            if (enableHiddenService) {
                config { _ ->
                    TorOption.HiddenServiceDir.tryConfigure {
                        directory(env.workDirectory.resolve(HS_DIR_NAME))
                        version(3)
                        port(virtual = ONION_VIRTUAL_PORT.toPort()) {
                            target(port = localPort.toPort())
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun start(localPort: Int, enableHiddenService: Boolean) {
        if (runtime != null) return
        lastLocalPort = localPort
        lastEnableHiddenService = enableHiddenService
        _state.value = State.Bootstrapping
        val rt = try {
            buildRuntime(localPort, enableHiddenService)
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Failed to build TorRuntime", e)
            _state.value = State.Error(e.message ?: "tor build failed")
            return
        }
        runtime = rt
        Citrine.instance.applicationScope.launch {
            try {
                rt.startDaemonAsync()
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Failed to start Tor daemon", e)
                _state.value = State.Error(e.message ?: "tor start failed")
            }
        }
    }

    fun retry() {
        val port = lastLocalPort.takeIf { it > 0 } ?: Settings.port
        val enableHs = lastEnableHiddenService || Settings.useTor
        stop()
        start(port, enableHs)
    }

    @Synchronized
    fun stop() {
        val rt = runtime ?: run {
            _state.value = State.Off
            return
        }
        runtime = null
        _socksPort.value = null
        HttpClientManager.setDefaultProxy(null)
        Citrine.instance.applicationScope.launch {
            try {
                rt.stopDaemonAsync()
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Failed to stop Tor daemon", e)
            } finally {
                _state.value = State.Off
            }
        }
    }
}
