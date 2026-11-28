package com.greenart7c3.citrine

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.okhttp.HttpClientManager
import com.greenart7c3.citrine.relays.AppRelayClient
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.service.EventDownloader
import com.greenart7c3.citrine.service.EventRebroadcaster
import com.greenart7c3.citrine.service.ListImporter
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.NsiteManager
import com.greenart7c3.citrine.service.PokeyListener
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.service.RelayIdentity
import com.greenart7c3.citrine.service.TorManager
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.service.crashreports.CrashReportCache
import com.greenart7c3.citrine.service.crashreports.CrashReportSender
import com.greenart7c3.citrine.service.crashreports.ExitReasons
import com.greenart7c3.citrine.service.crashreports.ReportAssembler
import com.greenart7c3.citrine.service.crashreports.UnexpectedCrashSaver
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Citrine :
    Application(),
    SingletonImageLoader.Factory {
    val crashReportCache: CrashReportCache by lazy { CrashReportCache(this.applicationContext) }

    // Coil image loader whose network fetches route through the app's OkHttp client so nsite
    // icon downloads honor the relay's Tor/proxy fail-closed policy instead of going direct.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = { HttpClientManager.getHttpClient(Settings.proxyAllUrls) },
                ),
            )
        }
        .build()
    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("AmberCoroutine", "Caught exception: ${throwable.message}", throwable)
        }
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    val relayClient: RelayClient = AppRelayClient(applicationScope)

    init {
        EventSubscription.install(applicationScope, exceptionHandler) { error ->
            crashReportCache.appendReport(ReportAssembler(this).buildReport(error))
        }
        EventRebroadcaster.install(relayClient)
        ListImporter.install(relayClient)
        CrashReportSender.install(relayClient, applicationScope)
        EventDownloader.install(this, relayClient, applicationScope)
        RelayIdentity.install(this)
        GroupManager.install(this, applicationScope)
        NsiteManager.install(this, relayClient)
        TorManager.install(this, applicationScope)
        RelayAggregator.install(this, relayClient, exceptionHandler)
    }

    val pokeyListener = PokeyListener(this)

    override fun onCreate() {
        super.onCreate()

        Log.init(this)

        Thread.setDefaultUncaughtExceptionHandler(UnexpectedCrashSaver(crashReportCache))

        // Record why the previous process died (ANR, native crash, excessive resource usage) while
        // it can still be read, and leave it in the in-app log as well as the crash dialog.
        applicationScope.launch(Dispatchers.IO) {
            try {
                ExitReasons.detect(this@Citrine)
            } catch (e: Exception) {
                Log.e(TAG, "Could not read previous exit reasons", e)
            }
        }

        relayClient.client.connect()

        LocalPreferences.loadSettingsFromEncryptedStorage(this)
        if (Settings.listenToPokeyBroadcasts) {
            pokeyListener.register()
        }
    }

    private val ephemeralCleanupScheduled = AtomicBoolean(false)

    // Monotonic generation incremented whenever a new ephemeral event is stored so a
    // racing cleanup pass never clears [hasEphemeralEvents] after a newer insert.
    private val ephemeralGeneration = AtomicLong(1L)

    // True on startup so the first cleanup sweep catches any ephemeral events left
    // behind before a restart; cleared once the table holds no ephemeral events and
    // re-set whenever a new ephemeral event is stored.
    @Volatile
    private var hasEphemeralEvents = true

    fun scheduleEphemeralCleanup(database: AppDatabase) {
        ephemeralGeneration.incrementAndGet()
        hasEphemeralEvents = true
        ensureEphemeralCleanupRunning(database)
    }

    private fun ensureEphemeralCleanupRunning(database: AppDatabase) {
        if (!hasEphemeralEvents || !ephemeralCleanupScheduled.compareAndSet(false, true)) return
        applicationScope.launch(Dispatchers.IO) {
            try {
                while (isActive && hasEphemeralEvents) {
                    // Wait slightly past the 1-minute ephemeral TTL so a burst of ephemeral
                    // events is cleaned in one pass; loop again if newer ones remain.
                    delay(75_000L)
                    if (isImportingEvents) continue
                    val genBefore = ephemeralGeneration.get()
                    database.eventDao().deleteEphemeralEvents(TimeUtils.oneMinuteAgo())
                    if (!database.eventDao().hasEphemeralEvents()) {
                        if (ephemeralGeneration.get() == genBefore) {
                            hasEphemeralEvents = false
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error deleting ephemeral events", e)
            } finally {
                ephemeralCleanupScheduled.set(false)
            }
            // Re-arm if an ephemeral event arrived in the narrow window between exiting
            // the loop and releasing ephemeralCleanupScheduled.
            if (hasEphemeralEvents) {
                ensureEphemeralCleanupRunning(database)
            }
        }
    }

    suspend fun eventsToDelete(database: AppDatabase, runAgeRetention: Boolean = true) {
        if (!isImportingEvents) {
            val verbose = Log.isLoggable(TAG, Log.DEBUG)
            if (verbose) Log.d(TAG, "entered eventsToDelete")
            job?.join()
            cancelJob()
            job = applicationScope.launch(Dispatchers.IO) {
                try {
                    if (isActive && hasEphemeralEvents) {
                        val duration = measureTime {
                            if (verbose) Log.d(TAG, "Deleting ephemeral events older than one minute ago")
                            val genBefore = ephemeralGeneration.get()
                            database.eventDao().deleteEphemeralEvents(TimeUtils.oneMinuteAgo())
                            if (!database.eventDao().hasEphemeralEvents() && ephemeralGeneration.get() == genBefore) {
                                hasEphemeralEvents = false
                            }
                        }
                        if (verbose) Log.d(TAG, "Deleted ephemeral events in $duration")
                    }

                    if (isActive) {
                        val duration = measureTime {
                            if (verbose) Log.d(TAG, "Deleting expired events")
                            database.eventDao().deleteEventsWithExpirations(TimeUtils.now())
                        }
                        if (verbose) Log.d(TAG, "Deleted expired events in $duration")
                    }

                    if (runAgeRetention && Settings.deleteEventsOlderThan != OlderThan.NEVER && isActive) {
                        val until = when (Settings.deleteEventsOlderThan) {
                            OlderThan.DAY -> TimeUtils.oneDayAgo()
                            OlderThan.WEEK -> TimeUtils.oneWeekAgo()
                            OlderThan.MONTH -> TimeUtils.now() - TimeUtils.ONE_MONTH
                            OlderThan.YEAR -> TimeUtils.now() - TimeUtils.ONE_YEAR
                            else -> 0
                        }
                        if (until > 0) {
                            val duration = measureTime {
                                if (verbose) Log.d(TAG, "Deleting old events (older than ${Settings.deleteEventsOlderThan})")
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
                            if (verbose) Log.d(TAG, "Deleted old events in $duration")
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

        // True on startup so the first maintenance tick clears any stale progress
        // notification left behind by a prior crash; set whenever notification id 2 is
        // posted and cleared once cancelled, avoiding redundant Binder IPCs when idle.
        @Volatile
        var hasProgressNotification = true

        const val TAG = "Citrine"

        fun cancelJob() {
            job?.cancelChildren()
            job?.cancel()
        }
    }
}

val Context.citrine: Citrine get() = applicationContext as Citrine
