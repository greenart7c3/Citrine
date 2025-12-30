package com.greenart7c3.citrine

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
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
        HttpClientManager.getHttpClient(if (isPrivateIp(url.url)) false else Settings.useProxy)
    }
    val client: NostrClient = NostrClient(factory, applicationScope)

    private val pokeyReceiver = PokeyReceiver()
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    fun isPrivateIp(url: String): Boolean = url.contains("127.0.0.1") ||
        url.contains("localhost") ||
        url.contains("192.168.") ||
        url.contains("172.16.") ||
        url.contains("172.17.") ||
        url.contains("172.18.") ||
        url.contains("172.19.") ||
        url.contains("172.20.") ||
        url.contains("172.21.") ||
        url.contains("172.22.") ||
        url.contains("172.23.") ||
        url.contains("172.24.") ||
        url.contains("172.25.") ||
        url.contains("172.26.") ||
        url.contains("172.27.") ||
        url.contains("172.28.") ||
        url.contains("172.29.") ||
        url.contains("172.30.") ||
        url.contains("172.31.")

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

        client.connect()

        LocalPreferences.loadSettingsFromEncryptedStorage(this)
        if (Settings.listenToPokeyBroadcasts) {
            registerPokeyReceiver()
        }

        // Register connectivity callback for Android 7.0+ (API 24+)
        // CONNECTIVITY_ACTION broadcasts don't work reliably on Android 7.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerConnectivityCallback()
        }
    }

    private fun registerConnectivityCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available, reconnecting Nostr client")
                applicationScope.launch {
                    try {
                        if (!client.isActive()) {
                            client.connect()
                            Log.d(TAG, "Nostr client reconnected after network became available")
                        } else {
                            Log.d(TAG, "Nostr client already active, skipping reconnect")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reconnect Nostr client after network became available", e)
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                Log.d(TAG, "Network capabilities changed: hasInternet=$hasInternet, isValidated=$isValidated")

                if (hasInternet && isValidated) {
                    Log.d(TAG, "Network validated, ensuring Nostr client is connected")
                    applicationScope.launch {
                        try {
                            if (!client.isActive()) {
                                client.connect()
                                Log.d(TAG, "Nostr client reconnected after network validation")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reconnect Nostr client after network validation", e)
                        }
                    }
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback!!)
            Log.d(TAG, "Registered connectivity callback for Android ${Build.VERSION.SDK_INT}+")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connectivity callback", e)
        }
    }

    private fun unregisterConnectivityCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        connectivityCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
                Log.d(TAG, "Unregistered connectivity callback")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister connectivity callback", e)
            }
        }
        connectivityCallback = null
    }

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
        lateinit var instance: Citrine
            private set
    }
}
