package com.greenart7c3.citrine

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.okhttp.OkHttpWebSocket
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.PokeyReceiver
import com.greenart7c3.citrine.service.WebSocketServerService
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.IRelayClientListener
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayState
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Citrine : Application() {
    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("AmberCoroutine", "Caught exception: ${throwable.message}", throwable)
        }
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    val factory = OkHttpWebSocket.Builder { url ->
        // TODO: setup proxy as a settings preference
        HttpClientManager.getHttpClient(false)
    }
    val client: NostrClient = NostrClient(factory, applicationScope)

    private val pokeyReceiver = PokeyReceiver()

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerPokeyReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                pokeyReceiver,
                IntentFilter(PokeyReceiver.POKEY_ACTION),
                RECEIVER_EXPORTED,
            )
        } else {
            registerReceiver(
                pokeyReceiver,
                IntentFilter(PokeyReceiver.POKEY_ACTION),
            )
        }
    }

    fun unregisterPokeyReceiver() {
        unregisterReceiver(pokeyReceiver)
    }

    override fun onCreate() {
        super.onCreate()

        instance = this
        LocalPreferences.loadSettingsFromEncryptedStorage(this)
        if (Settings.listenToPokeyBroadcasts) {
            registerPokeyReceiver()
        }
    }

    fun contentResolverFn(): ContentResolver = contentResolver

    fun cancelJob() {
        job?.cancelChildren()
        job?.cancel()
    }

    suspend fun eventsToDelete(database: AppDatabase) {
        if (!isImportingEvents) {
            Log.d(TAG, "entered eventsToDelete")
            job?.join()
            cancelJob()
            job = applicationScope.launch(Dispatchers.IO) {
                try {
                    if (Settings.deleteEphemeralEvents && isActive) {
                        val duration = measureTime {
                            Log.d(TAG, "Deleting ephemeral events older than one minute ago")
                            database.eventDao().deleteEphemeralEvents(TimeUtils.oneMinuteAgo())
                        }
                        Log.d(TAG, "Deleted ephemeral events in $duration")
                    }

                    if (Settings.deleteExpiredEvents && isActive) {
                        val duration = measureTime {
                            Log.d(TAG, "Deleting expired events")
                            database.eventDao().deleteEventsWithExpirations(TimeUtils.now())
                        }
                        Log.d(TAG, "Deleted expired events in $duration")
                    }

                    if (Settings.deleteEventsOlderThan != OlderThan.NEVER && isActive) {
                        val until = when (Settings.deleteEventsOlderThan) {
                            OlderThan.DAY -> TimeUtils.oneDayAgo()
                            OlderThan.WEEK -> TimeUtils.oneWeekAgo()
                            OlderThan.MONTH -> TimeUtils.now() - TimeUtils.ONE_MONTH
                            OlderThan.YEAR -> TimeUtils.now() - TimeUtils.ONE_YEAR
                            else -> 0
                        }
                        if (until > 0) {
                            val duration = measureTime {
                                Log.d(TAG, "Deleting old events (older than ${Settings.deleteEventsOlderThan})")
                                if (Settings.neverDeleteFrom.isNotEmpty()) {
                                    database.eventDao().deleteAll(until, Settings.neverDeleteFrom.toTypedArray())
                                } else {
                                    database.eventDao().deleteAll(until)
                                }
                            }
                            Log.d(TAG, "Deleted old events in $duration")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error deleting events", e)
                }
            }
        }
    }

    fun startService() {
        try {
            val operation = PendingIntent.getForegroundService(
                this,
                10,
                Intent(this, WebSocketServerService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val alarmManager = this.getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.set(AlarmManager.RTC_WAKEUP, 1000, operation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocketServerService", e)
        }
    }

    companion object {

        @Volatile
        var job: Job? = null

        @Volatile
        var isImportingEvents = false

        const val TAG = "Citrine"

        @Volatile
        private var instance: Citrine? = null

        fun getInstance(): Citrine = instance ?: synchronized(this) {
            instance ?: Citrine().also { instance = it }
        }
    }
}

class RelayListener(
    val onReceiveEvent: (relay: IRelayClient, subscriptionId: String, event: Event) -> Unit,
) : IRelayClientListener {
    override fun onAuth(relay: IRelayClient, challenge: String) {
        Log.d("RelayListener", "Received auth challenge $challenge from relay ${relay.url}")
    }

    override fun onBeforeSend(relay: IRelayClient, event: Event) {
        Log.d("RelayListener", "Sending event ${event.id} to relay ${relay.url}")
    }

    override fun onError(relay: IRelayClient, subId: String, error: Error) {
        Log.d("RelayListener", "Received error $error from subscription $subId")
    }

    override fun onEvent(relay: IRelayClient, subId: String, event: Event, arrivalTime: Long, afterEOSE: Boolean) {
        Log.d("RelayListener", "Received event ${event.toJson()} from subscription $subId afterEOSE: $afterEOSE")
        onReceiveEvent(relay, subId, event)
    }

    override fun onEOSE(relay: IRelayClient, subId: String, arrivalTime: Long) {
        Log.d("RelayListener", "Received EOSE from subscription $subId")
    }

    override fun onNotify(relay: IRelayClient, description: String) {
        Log.d("RelayListener", "Received notify $description from relay ${relay.url}")
    }

    override fun onRelayStateChange(relay: IRelayClient, type: RelayState) {
        Log.d("RelayListener", "Relay ${relay.url} state changed to $type")
    }

    override fun onSend(relay: IRelayClient, msg: String, success: Boolean) {
        Log.d("RelayListener", "Sent message $msg to relay ${relay.url} success: $success")
    }

    override fun onSendResponse(relay: IRelayClient, eventId: String, success: Boolean, message: String) {
        Log.d("RelayListener", "Sent response to event $eventId to relay ${relay.url} success: $success message: $message")
    }
}

class RelayListener2(
    val onReceiveEvent: (relay: IRelayClient, subscriptionId: String, event: Event) -> Unit,
    val onEOSE: (relay: IRelayClient) -> Unit,
    val onErrorFunc: (relay: IRelayClient, subscriptionId: String, error: Error) -> Unit,
    val onAuthFunc: (relay: IRelayClient, challenge: String) -> Unit,
) : IRelayClientListener {
    override fun onAuth(relay: IRelayClient, challenge: String) {
        Log.d("RelayListener", "Received auth challenge $challenge from relay ${relay.url}")
        onAuthFunc(relay, challenge)
    }

    override fun onBeforeSend(relay: IRelayClient, event: Event) {
        Log.d("RelayListener", "Sending event ${event.id} to relay ${relay.url}")
    }

    override fun onError(relay: IRelayClient, subId: String, error: Error) {
        Log.d("RelayListener", "Received error $error from subscription $subId")
        onErrorFunc(relay, subId, error)
    }

    override fun onEvent(relay: IRelayClient, subId: String, event: Event, arrivalTime: Long, afterEOSE: Boolean) {
        // Log.d("RelayListener", "Received event ${event.toJson()} from subscription $subscriptionId afterEOSE: $afterEOSE")
        onReceiveEvent(relay, subId, event)
    }

    override fun onEOSE(relay: IRelayClient, subId: String, arrivalTime: Long) {
        Log.d("RelayListener", "Received EOSE from subscription $subId")
        onEOSE(relay)
    }

    override fun onNotify(relay: IRelayClient, description: String) {
        Log.d("RelayListener", "Received notify $description from relay ${relay.url}")
    }

    override fun onRelayStateChange(relay: IRelayClient, type: RelayState) {
        Log.d("RelayListener", "Relay ${relay.url} state changed to $type")
        if (type == RelayState.DISCONNECTING) {
            onEOSE(relay)
        }
    }

    override fun onSend(relay: IRelayClient, msg: String, success: Boolean) {
        Log.d("RelayListener", "Sent message $msg to relay ${relay.url} success: $success")
    }

    override fun onSendResponse(relay: IRelayClient, eventId: String, success: Boolean, message: String) {
        Log.d("RelayListener", "Sent response to event $eventId to relay ${relay.url} success: $success message: $message")
    }
}
