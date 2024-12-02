package com.greenart7c3.citrine.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anggrayudi.storage.file.extension
import com.anggrayudi.storage.file.openInputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.RelayListener
import com.greenart7c3.citrine.RelayListener2
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.vitorpamplona.ammolite.relays.COMMON_FEED_TYPES
import com.vitorpamplona.ammolite.relays.Relay
import com.vitorpamplona.ammolite.relays.RelayPool
import com.vitorpamplona.ammolite.relays.TypedFilter
import com.vitorpamplona.ammolite.relays.filters.SincePerRelayFilter
import com.vitorpamplona.quartz.events.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.events.ContactListEvent
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.RelayAuthEvent
import com.vitorpamplona.quartz.signers.ExternalSignerLauncher
import com.vitorpamplona.quartz.signers.NostrSignerExternal
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class HomeState(
    val loading: Boolean = false,
    val bound: Boolean = false,
    val service: WebSocketServerService? = null,
    val pubKey: String = "",
    val progress: String = "",
)

class HomeViewModel : ViewModel() {
    init {
        Log.d(Citrine.TAG, "HomeViewModel init")
    }

    private val _state = MutableStateFlow(HomeState())
    val state = _state
    var signer = NostrSignerExternal(
        "",
        ExternalSignerLauncher("", ""),
    )

    fun loadEventsFromPubKey(database: AppDatabase) {
        if (state.value.pubKey.isNotBlank()) {
            _state.value = _state.value.copy(
                progress = "loading contact list",
                loading = true,
            )

            viewModelScope.launch(Dispatchers.IO) {
                getAllEventsFromPubKey(
                    database = database,
                )
            }
        }
    }

    private suspend fun getAllEventsFromPubKey(
        database: AppDatabase,
    ) {
        var contactList = database.eventDao().getContactList(signer.pubKey)
        if (contactList == null) {
            fetchContactList(
                onDone = {
                    state.value = state.value.copy(
                        progress = "loading relays",
                    )
                    contactList = database.eventDao().getContactList(signer.pubKey)
                    viewModelScope.launch(Dispatchers.IO) {
                        var generalRelays: Map<String, ContactListEvent.ReadWrite>? = null
                        contactList?.let {
                            generalRelays = (it.toEvent() as ContactListEvent).relays()
                        }

                        generalRelays?.forEach {
                            if (RelayPool.getRelay(it.key) == null) {
                                RelayPool.addRelay(
                                    Relay(
                                        url = it.key,
                                        read = true,
                                        write = false,
                                        forceProxy = false,
                                        activeTypes = COMMON_FEED_TYPES,
                                    ),
                                )
                            }
                        }

                        fetchOutbox(
                            onDone = {
                                launch(Dispatchers.IO) {
                                    val advertisedRelayList = database.eventDao().getAdvertisedRelayList(signer.pubKey)
                                    advertisedRelayList?.let {
                                        val relays = (it.toEvent() as AdvertisedRelayListEvent).relays()
                                        relays.forEach { relay ->
                                            if (RelayPool.getRelay(relay.relayUrl) == null) {
                                                RelayPool.addRelay(
                                                    Relay(
                                                        url = relay.relayUrl,
                                                        read = true,
                                                        write = false,
                                                        forceProxy = false,
                                                        activeTypes = COMMON_FEED_TYPES,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    fetchEvents(
                                        onAuth = { relay, challenge ->
                                            RelayAuthEvent.create(
                                                relay.url,
                                                challenge,
                                                signer,
                                                onReady = {
                                                    relay.send(it)
                                                },
                                            )
                                        },
                                    )
                                    state.value = state.value.copy(
                                        pubKey = "",
                                    )
                                }
                            },
                        )
                    }
                },
            )
        } else {
            state.value = state.value.copy(
                progress = "loading relays",
            )
            var generalRelays: Map<String, ContactListEvent.ReadWrite>? = null
            contactList?.let {
                generalRelays = (it.toEvent() as ContactListEvent).relays()
            }

            generalRelays?.forEach {
                if (RelayPool.getRelay(it.key) == null) {
                    RelayPool.addRelay(
                        Relay(
                            url = it.key,
                            read = true,
                            write = false,
                            forceProxy = false,
                            activeTypes = COMMON_FEED_TYPES,
                        ),
                    )
                }
            }
            var advertisedRelayList = database.eventDao().getAdvertisedRelayList(signer.pubKey)
            if (advertisedRelayList == null) {
                fetchOutbox(
                    onDone = {
                        viewModelScope.launch(Dispatchers.IO) {
                            advertisedRelayList = database.eventDao().getAdvertisedRelayList(signer.pubKey)
                            advertisedRelayList?.let {
                                val relays = (it.toEvent() as AdvertisedRelayListEvent).relays()
                                relays.forEach { relay ->
                                    if (RelayPool.getRelay(relay.relayUrl) == null) {
                                        RelayPool.addRelay(
                                            Relay(
                                                url = relay.relayUrl,
                                                read = true,
                                                write = false,
                                                forceProxy = false,
                                                activeTypes = COMMON_FEED_TYPES,
                                            ),
                                        )
                                    }
                                }
                            }
                            fetchEvents(
                                onAuth = { relay, challenge ->
                                    RelayAuthEvent.create(
                                        relay.url,
                                        challenge,
                                        signer,
                                        onReady = {
                                            relay.send(it)
                                        },
                                    )
                                },
                            )
                            state.value = state.value.copy(
                                pubKey = "",
                            )
                        }
                    },
                )
            } else {
                advertisedRelayList?.let {
                    val relays = (it.toEvent() as AdvertisedRelayListEvent).relays()
                    relays.forEach { relay ->
                        if (RelayPool.getRelay(relay.relayUrl) == null) {
                            RelayPool.addRelay(
                                Relay(
                                    url = relay.relayUrl,
                                    read = true,
                                    write = false,
                                    forceProxy = false,
                                    activeTypes = COMMON_FEED_TYPES,
                                ),
                            )
                        }
                    }
                }

                fetchEvents(
                    onAuth = { relay, challenge ->
                        RelayAuthEvent.create(
                            relay.url,
                            challenge,
                            signer,
                            onReady = {
                                relay.send(it)
                            },
                        )
                    },
                )
                state.value = state.value.copy(
                    pubKey = "",
                )
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketServerService.LocalBinder
            _state.value = _state.value.copy(
                service = binder.getService(),
                bound = true,
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _state.value = _state.value.copy(
                bound = false,
            )
        }
    }

    fun setLoading(loading: Boolean) {
        _state.value = _state.value.copy(loading = loading)
    }

    suspend fun stop(context: Context) {
        try {
            setLoading(true)
            val intent = Intent(context, WebSocketServerService::class.java)
            context.stopService(intent)
            if (state.value.bound) context.unbindService(connection)
            _state.value = _state.value.copy(
                service = null,
                bound = false,
            )
            delay(2000)
        } catch (e: Exception) {
            setLoading(false)
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.message ?: "", e)
        } finally {
            setLoading(false)
        }
    }

    suspend fun start(context: Context) {
        if (state.value.service?.isStarted() == true) {
            return
        }

        try {
            setLoading(true)
            val intent = Intent(context, WebSocketServerService::class.java)
            context.startService(intent)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            delay(2000)
        } catch (e: Exception) {
            setLoading(false)
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.message ?: "", e)
        } finally {
            setLoading(false)
        }
    }

    private suspend fun fetchContactList(
        onDone: () -> Unit,
    ) {
        val relays = listOf(
            Relay(
                url = "wss://purplepag.es",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val listener = RelayListener(
            onReceiveEvent = { relay, _, event ->
                Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
                runBlocking {
                    CustomWebSocketService.server?.innerProccesEvent(event, null)
                }
                finishedRelays[relay.url] = true
            },
        )

        relays.forEach {
            it.register(listener)
            it.connectAndRun { relay ->
                relay.sendFilter(
                    UUID.randomUUID().toString().substring(0, 4),
                    filters = listOf(
                        TypedFilter(
                            types = COMMON_FEED_TYPES,
                            filter = SincePerRelayFilter(
                                kinds = listOf(ContactListEvent.KIND),
                                authors = listOf(signer.pubKey),
                                limit = 1,
                            ),
                        ),
                    ),
                )
            }
        }
        var count = 0
        while (finishedRelays.values.contains(false) && count < 15) {
            count++
            delay(1000)
        }
        relays.forEach {
            it.unregister(listener)
        }
        onDone()
    }

    private suspend fun fetchOutbox(
        onDone: () -> Unit,
    ) {
        val relays = listOf(
            Relay(
                url = "wss://purplepag.es",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
            Relay(
                url = "wss://relay.nostr.band",
                read = true,
                write = false,
                forceProxy = false,
                activeTypes = COMMON_FEED_TYPES,
            ),
        )
        val finishedRelays = mutableMapOf<String, Boolean>()
        relays.forEach {
            finishedRelays[it.url] = false
        }

        val listener = RelayListener(
            onReceiveEvent = { relay, _, event ->
                Log.d(Citrine.TAG, "Received event ${event.toJson()} from relay")
                runBlocking {
                    CustomWebSocketService.server?.innerProccesEvent(event, null)
                }
                finishedRelays[relay.url] = true
            },
        )

        relays.forEach {
            it.register(listener)
            it.connectAndRun { relay ->
                relay.sendFilter(
                    UUID.randomUUID().toString().substring(0, 4),
                    filters = listOf(
                        TypedFilter(
                            types = COMMON_FEED_TYPES,
                            filter = SincePerRelayFilter(
                                kinds = listOf(AdvertisedRelayListEvent.KIND),
                                authors = listOf(signer.pubKey),
                                limit = 1,
                            ),
                        ),
                    ),
                )
            }
        }
        var count = 0
        while (finishedRelays.values.contains(false) && count < 15) {
            count++
            delay(1000)
        }
        relays.forEach {
            it.unregister(listener)
        }
        onDone()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun fetchEventsFrom(
        listeners: MutableMap<String, Relay.Listener>,
        relayParam: Relay,
        until: Long,
        onEvent: () -> Unit,
        onAuth: (relay: Relay, challenge: String) -> Unit,
        onDone: () -> Unit,
    ) {
        val subId = UUID.randomUUID().toString().substring(0, 4)
        val eventCount = mutableMapOf<String, Int>()
        var lastEventCreatedAt = until

        listeners[relayParam.url]?.let {
            relayParam.unregister(it)
        }

        val listener = RelayListener2(
            onReceiveEvent = { relay, _, event ->
                onEvent()
                eventCount[relay.url] = eventCount[relay.url]?.plus(1) ?: 1
                lastEventCreatedAt = event.createdAt

                runBlocking {
                    CustomWebSocketService.server?.innerProccesEvent(event, null)
                }
            },
            onEOSE = { relay ->
                Log.d(Citrine.TAG, "Received EOSE from relay ${relay.url}")
                relay.close(subId)
                listeners[relay.url]?.let {
                    relayParam.unregister(it)
                }
                GlobalScope.launch(Dispatchers.IO) {
                    if ((eventCount[relay.url] ?: 0) > 0) {
                        if (lastEventCreatedAt == until) {
                            onDone()
                        } else {
                            fetchEventsFrom(listeners, relay, lastEventCreatedAt, onEvent, onAuth, onDone)
                        }
                    } else {
                        onDone()
                    }
                }
            },
            onErrorFunc = { relay, sub, error ->
                if (error.message?.contains("Socket closed") == false) {
                    if (relay.isConnected()) {
                        relay.close(sub)
                    }
                    listeners[relay.url]?.let {
                        relayParam.unregister(it)
                    }
                    onDone()
                }
            },
            onAuthFunc = { relay, challenge ->
                onAuth(relay, challenge)
            },
        )

        val filters = listOf(
            TypedFilter(
                types = COMMON_FEED_TYPES,
                filter = SincePerRelayFilter(
                    authors = listOf(signer.pubKey),
                    until = until,
                ),
            ),
        )

        eventCount[relayParam.url] = 0
        relayParam.register(listener)
        listeners[relayParam.url] = listener
        if (!relayParam.isConnected()) {
            relayParam.connectAndRun { relay ->
                relay.sendFilter(
                    subId,
                    filters = filters,
                )
            }
        } else {
            relayParam.sendFilter(
                subId,
                filters = filters,
            )
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    suspend fun fetchEvents(
        onAuth: (relay: Relay, challenge: String) -> Unit,
    ) {
        val finishedLoading = mutableMapOf<String, Boolean>()

        RelayPool.getAll().filter { !it.url.contains("127.0.0.1") }.forEach {
            finishedLoading[it.url] = false
        }

        RelayPool.getAll().forEach {
            var count = 0
            state.value = state.value.copy(
                progress = "loading events from ${it.url}",
            )
            val listeners = mutableMapOf<String, Relay.Listener>()

            fetchEventsFrom(
                listeners = listeners,
                relayParam = it,
                until = TimeUtils.now(),
                onAuth = { relay, challenge ->
                    onAuth(relay, challenge)
                    GlobalScope.launch(Dispatchers.IO) {
                        delay(5000)

                        fetchEventsFrom(
                            listeners = listeners,
                            relayParam = it,
                            until = TimeUtils.now(),
                            onAuth = { _, _ ->
                                finishedLoading[it.url] = true
                            },
                            onEvent = {
                                count++
                                state.value = state.value.copy(
                                    progress = "loading events from ${it.url} ($count)",
                                )
                            },
                            onDone = {
                                finishedLoading[it.url] = true
                            },
                        )
                    }
                },
                onEvent = {
                    count++
                    state.value = state.value.copy(
                        progress = "loading events from ${it.url} ($count)",
                    )
                },
                onDone = {
                    finishedLoading[it.url] = true
                },
            )
            var timeElapsed = 0
            while (finishedLoading[it.url] == false) {
                delay(1000)
                timeElapsed++
                if (timeElapsed > 60 && count == 0) {
                    break
                }
            }
            if (it.isConnected()) {
                it.disconnect()
            }
        }

        RelayPool.disconnect()
        RelayPool.unloadRelays()
        delay(5000)

        state.value = state.value.copy(
            loading = false,
            progress = "",
        )
    }

    fun setPubKey(returnedKey: String) {
        _state.value = _state.value.copy(
            pubKey = returnedKey,
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun exportDatabase(
        folder: DocumentFile,
        database: AppDatabase,
        context: Context,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            setLoading(true)

            try {
                ExportDatabaseUtils.exportDatabase(
                    database,
                    context,
                    folder,
                ) {
                    _state.value = _state.value.copy(
                        progress = it,
                    )
                }
            } finally {
                _state.value = _state.value.copy(
                    progress = "",
                    loading = false,
                )
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun importDatabase(
        files: List<DocumentFile>,
        shouldDelete: Boolean,
        database: AppDatabase,
        context: Context,
        onFinished: () -> Unit,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            val file = files.first()
            if (file.extension != "jsonl") {
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.invalid_file_extension),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }

            try {
                state.value = state.value.copy(
                    loading = true,
                    progress = context.getString(R.string.reading_file, file.name),
                )
                var totalLines = 0

                val input = file.openInputStream(context) ?: return@launch
                input.use { ip ->
                    ip.bufferedReader().use {
                        var line: String?
                        while (it.readLine().also { readLine -> line = readLine } != null) {
                            if (line?.isNotBlank() == true) {
                                totalLines++
                            }
                        }
                    }
                }
                val input2 = file.openInputStream(context) ?: return@launch
                input2.use { ip ->
                    ip.bufferedReader().use {
                        var linesRead = 0

                        if (shouldDelete) {
                            _state.value = _state.value.copy(
                                progress = context.getString(R.string.deleting_all_events),
                            )
                            database.eventDao().deleteAll()
                        }

                        it.useLines { lines ->
                            lines.forEach { line ->
                                if (line.isBlank()) {
                                    return@forEach
                                }
                                val event = Event.fromJson(line)
                                CustomWebSocketService.server?.innerProccesEvent(event, null)

                                linesRead++
                                _state.value = _state.value.copy(
                                    progress = context.getString(R.string.imported, linesRead.toString(), totalLines.toString()),
                                )
                            }
                        }
                        RelayPool.disconnect()
                        delay(3000)
                        RelayPool.unloadRelays()
                    }
                }

                _state.value = _state.value.copy(
                    progress = "",
                    loading = false,
                )

                onFinished()
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.imported_events_successfully, totalLines),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                _state.value = _state.value.copy(
                    progress = "",
                    loading = false,
                )
                onFinished()
            }
        }
    }

    override fun onCleared() {
        Log.d(Citrine.TAG, "HomeViewModel cleared")
        if (state.value.bound) {
            Citrine.getInstance().unbindService(connection)
            _state.value = _state.value.copy(
                bound = false,
            )
        }
        super.onCleared()
    }
}
