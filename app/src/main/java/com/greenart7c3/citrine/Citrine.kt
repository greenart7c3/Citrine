package com.greenart7c3.citrine

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
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
import com.greenart7c3.citrine.service.crashreports.CrashReportCache
import com.greenart7c3.citrine.service.crashreports.UnexpectedCrashSaver
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
    val crashReportCache: CrashReportCache by lazy { CrashReportCache(this.applicationContext) }
    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("AmberCoroutine", "Caught exception: ${throwable.message}", throwable)
        }
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    val factory = OkHttpWebSocket.Builder { url ->
        HttpClientManager.getHttpClient(shouldUseProxyFor(url.url))
    }

    fun shouldUseProxyFor(url: String): Boolean {
        if (!Settings.useProxy) return false
        if (isPrivateIp(url)) return false
        if (Settings.proxyAllUrls) return true
        return isOnionUrl(url)
    }

    fun isOnionUrl(url: String): Boolean {
        val host = url
            .substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .lowercase()
        return host.endsWith(".onion")
    }
    val client: NostrClient = NostrClient(factory, applicationScope)

    private val pokeyReceiver = PokeyReceiver()
    private var isPokeyReceiverRegistered = false

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
    @Synchronized
    fun registerPokeyReceiver() {
        if (isPokeyReceiverRegistered) return

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
        isPokeyReceiverRegistered = true
    }

    @Synchronized
    fun unregisterPokeyReceiver() {
        if (!isPokeyReceiverRegistered) return

        try {
            unregisterReceiver(pokeyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "PokeyReceiver was not registered", e)
        }
        isPokeyReceiverRegistered = false
    }

    override fun onCreate() {
        super.onCreate()

        instance = this

        Thread.setDefaultUncaughtExceptionHandler(UnexpectedCrashSaver(crashReportCache, applicationScope))

        client.connect()

        LocalPreferences.loadSettingsFromEncryptedStorage(this)
        if (Settings.listenToPokeyBroadcasts) {
            registerPokeyReceiver()
        }
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
                    if (isActive) {
                        val duration = measureTime {
                            Log.d(TAG, "Deleting ephemeral events older than one minute ago")
                            database.eventDao().deleteEphemeralEvents(TimeUtils.oneMinuteAgo())
                        }
                        Log.d(TAG, "Deleted ephemeral events in $duration")
                    }

                    if (isActive) {
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
                                val neverFrom = Settings.neverDeleteFrom
                                val preserved = Settings.preservedKindsFromDeletion
                                when {
                                    neverFrom.isEmpty() && preserved.isEmpty() ->
                                        database.eventDao().deleteAll(until)
                                    neverFrom.isEmpty() ->
                                        database.eventDao().deleteAll(until, preserved.toIntArray())
                                    preserved.isEmpty() ->
                                        database.eventDao().deleteAll(until, neverFrom.toTypedArray())
                                    else ->
                                        database.eventDao().deleteAll(until, neverFrom.toTypedArray(), preserved.toIntArray())
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
