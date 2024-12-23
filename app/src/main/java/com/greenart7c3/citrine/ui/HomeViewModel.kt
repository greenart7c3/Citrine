package com.greenart7c3.citrine.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class HomeState(
    val loading: Boolean = false,
    val bound: Boolean = false,
    val service: WebSocketServerService? = null,
    val pubKey: String = "",
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
            setProgress("loading contact list")

            getAllEventsFromPubKey(
                database = database,
            )
        }
    }

    private fun getAllEventsFromPubKey(
        database: AppDatabase,
    ) {
        Citrine.getInstance().cancelJob()
        Citrine.getInstance().job = Citrine.getInstance().applicationScope.launch {
            RelayPool.disconnect()
            RelayPool.unloadRelays()
            var contactList = database.eventDao().getContactList(signer.pubKey)
            if (contactList == null) {
                fetchContactList(
                    onDone = {
                        launch {
                            setProgress("loading relays")
                            contactList = database.eventDao().getContactList(signer.pubKey)

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
                                            scope = this,
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
                setProgress("loading relays")
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
                            launch {
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
                                    scope = this,
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
                        scope = this,
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
                    CustomWebSocketService.server?.innerProcessEvent(event, null)
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
                    CustomWebSocketService.server?.innerProcessEvent(event, null)
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

    private suspend fun fetchEventsFrom(
        scope: CoroutineScope,
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
        val limit = 100

        val listener = RelayListener2(
            onReceiveEvent = { relay, _, event ->
                onEvent()
                eventCount[relay.url] = eventCount[relay.url]?.plus(1) ?: 1
                lastEventCreatedAt = event.createdAt

                scope.launch {
                    CustomWebSocketService.server?.innerProcessEvent(event, null)
                }
            },
            onEOSE = { relay ->
                Log.d(Citrine.TAG, "Received EOSE from relay ${relay.url}")
                relay.close(subId)
                listeners[relay.url]?.let {
                    relayParam.unregister(it)
                }
                scope.launch(Dispatchers.IO) {
                    val localEventCount = eventCount[relay.url] ?: 0
                    if (localEventCount < limit) {
                        onDone()
                    } else {
                        fetchEventsFrom(scope, listeners, relay, lastEventCreatedAt, onEvent, onAuth, onDone)
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
                    limit = limit,
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

    private suspend fun fetchEvents(
        onAuth: (relay: Relay, challenge: String) -> Unit,
        scope: CoroutineScope,
    ) {
        val finishedLoading = mutableMapOf<String, Boolean>()

        RelayPool.getAll().filter { !it.url.contains("127.0.0.1") }.forEach {
            finishedLoading[it.url] = false
        }

        RelayPool.getAll().forEach {
            var count = 0
            setProgress("loading events from ${it.url}")
            val listeners = mutableMapOf<String, Relay.Listener>()

            fetchEventsFrom(
                scope = scope,
                listeners = listeners,
                relayParam = it,
                until = TimeUtils.now(),
                onAuth = { relay, challenge ->
                    onAuth(relay, challenge)
                    scope.launch(Dispatchers.IO) {
                        delay(5000)

                        fetchEventsFrom(
                            scope = scope,
                            listeners = listeners,
                            relayParam = it,
                            until = TimeUtils.now(),
                            onAuth = { _, _ ->
                                finishedLoading[it.url] = true
                            },
                            onEvent = {
                                count++
                                setProgress("loading events from ${it.url} ($count)")
                            },
                            onDone = {
                                finishedLoading[it.url] = true
                            },
                        )
                    }
                },
                onEvent = {
                    count++
                    setProgress("loading events from ${it.url} ($count)")
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

        setProgress("")
    }

    fun setPubKey(returnedKey: String) {
        _state.value = _state.value.copy(
            pubKey = returnedKey,
        )
    }

    fun exportDatabase(
        folder: DocumentFile,
        database: AppDatabase,
        context: Context,
    ) {
        Citrine.getInstance().cancelJob()
        Citrine.getInstance().job = Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            try {
                ExportDatabaseUtils.exportDatabase(
                    database,
                    context,
                    folder,
                ) {
                    setProgress(it)
                }
            } finally {
                setProgress("")
            }
        }
    }

    fun importDatabase(
        files: List<DocumentFile>,
        shouldDelete: Boolean,
        database: AppDatabase,
        context: Context,
        onFinished: () -> Unit,
    ) {
        Citrine.getInstance().cancelJob()
        Citrine.getInstance().job = Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            val file = files.first()
            if (file.extension != "jsonl") {
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.invalid_file_extension),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }

            try {
                Citrine.getInstance().isImportingEvents = true
                setProgress(context.getString(R.string.reading_file, file.name))

                var linesRead = 0
                val input2 = file.openInputStream(context) ?: return@launch
                input2.use { ip ->
                    ip.bufferedReader().use {
                        if (shouldDelete) {
                            setProgress(context.getString(R.string.deleting_all_events))
                            database.eventDao().deleteAll()
                        }

                        it.useLines { lines ->
                            lines.forEach { line ->
                                if (line.isBlank()) {
                                    return@forEach
                                }
                                val event = Event.fromJson(line)
                                CustomWebSocketService.server?.innerProcessEvent(event, null)

                                linesRead++
                                setProgress(context.getString(R.string.imported2, linesRead.toString()))
                            }
                        }
                        RelayPool.disconnect()
                        delay(3000)
                        RelayPool.unloadRelays()
                    }
                }

                setProgress(context.getString(R.string.imported_events_successfully, linesRead))
                Citrine.getInstance().isImportingEvents = false
                onFinished()
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.imported_events_successfully, linesRead),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Citrine.getInstance().isImportingEvents = false
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                setProgress("")
                setProgress(context.getString(R.string.import_failed))
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

    fun setProgress(message: String) {
        val notificationManager = NotificationManagerCompat.from(Citrine.getInstance())

        if (message.isBlank()) {
            notificationManager.cancel(2)
            return
        }

        val channel = NotificationChannelCompat.Builder(
            "citrine",
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName("Citrine")
            .build()

        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(Citrine.getInstance(), "citrine")
            .setContentTitle("Citrine")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .build()

        if (ActivityCompat.checkSelfPermission(Citrine.getInstance(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationManager.notify(2, notification)
    }
}
