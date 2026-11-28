package com.greenart7c3.citrine.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.MainActivity
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.TagDeduplicator
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.logs.LogDatabase
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.greenart7c3.citrine.utils.NetworkUtils
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val NOTIFICATION_THROTTLE_MS = 30_000L
private const val MAINTENANCE_INTERVAL_MS = 15L * 60 * 1000
private const val AGE_RETENTION_INTERVAL_MS = 60L * 60 * 1000
private const val LOG_TRIM_INTERVAL_MS = 6L * 60 * 60 * 1000
private const val ONE_WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
private const val ONE_DAY_SECONDS = 24L * 60 * 60
private const val FOREGROUND_NOTIFICATION_ID = 1
private const val BACKUP_PROGRESS_NOTIFICATION_ID = 2
private const val DATABASE_UPGRADE_NOTIFICATION_ID = 3

/**
 * Notification id for "nsite update available" prompts. Ids 1/2/3 are already used by the
 * foreground service, backup/download progress, and database-upgrade notifications.
 */
const val NSITE_UPDATE_NOTIFICATION_ID = 4

class WebSocketServerService : Service() {
    private val scope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob() + citrine.exceptionHandler) }
    private val binder = LocalBinder()
    private var timer: Timer? = null

    // Re-posting an unchanged notification can still wake the device and refresh the
    // system UI, even with setOnlyAlertOnce, so identical content is not re-posted.
    @Suppress("NoComments")
    @Volatile
    private var lastForegroundSignature: String? = null

    @Volatile private var lastBackupProgressText: String? = null

    @Volatile private var lastAgeRetentionMs: Long = 0L

    @Volatile private var lastLogTrimMs: Long = 0L
    inner class LocalBinder : Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(Citrine.TAG, "onStartCommand ${CustomWebSocketService.hasStarted}")
        return START_STICKY
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()

        Log.d(Citrine.TAG, "Starting WebSocket service")
        val database = AppDatabase.getDatabase(this@WebSocketServerService)

        Log.d(Citrine.TAG, "Starting timer")
        timer?.cancel()
        timer = Timer()
        timer?.schedule(
            object : TimerTask() {
                override fun run() {
                    runMaintenance(database)
                }
            },
            0,
            MAINTENANCE_INTERVAL_MS,
        )

        watchDatabaseUpgrade()

        startRelayServer(database)

        startOrStopTor()

        if (Settings.relayAggregatorEnabled) {
            RelayAggregator.start(database)
        }

        if (!Settings.duplicateTagsCleaned) {
            scheduleDuplicateTagCleanup(database)
        }

        startForegroundWithRetries()

        watchForegroundNotificationSources()
    }

    private fun runMaintenance(database: AppDatabase) {
        val nowMs = System.currentTimeMillis()
        if (isBackupIdle() && !Citrine.isImportingEvents) {
            if (lastBackupProgressText != null || Citrine.hasProgressNotification) {
                NotificationManagerCompat.from(citrine).cancel(BACKUP_PROGRESS_NOTIFICATION_ID)
                lastBackupProgressText = null
                Citrine.hasProgressNotification = false
            }
        }

        if (!Citrine.isImportingEvents) {
            scheduleEventCleanup(database, nowMs)
        }

        if (lastLogTrimMs == 0L || (nowMs - lastLogTrimMs) >= LOG_TRIM_INTERVAL_MS) {
            lastLogTrimMs = nowMs
            scheduleLogTrim()
        }

        val oneWeekAgo = TimeUtils.oneWeekAgo()
        if (isAutoBackupDue(oneWeekAgo)) {
            startAutoBackup(database)
        }

        if (!Citrine.isImportingEvents && Settings.nsites.isNotEmpty()) {
            if (isDailyNsiteCheckDue()) {
                persistNsiteCheckBeforeItRunsSoTheNextTickSkipsIt()
                scheduleNsiteUpdateCheck()
            }
        }
    }

    private fun isDailyNsiteCheckDue(): Boolean = Settings.lastNsiteCheck < TimeUtils.now() - ONE_DAY_SECONDS

    private fun persistNsiteCheckBeforeItRunsSoTheNextTickSkipsIt() {
        Settings.lastNsiteCheck = TimeUtils.now()
        LocalPreferences.saveSettingsToEncryptedStorage(Settings, citrine)
    }

    private fun isBackupIdle(): Boolean = Citrine.job == null || Citrine.job?.isCompleted == true

    private fun isAutoBackupDue(oneWeekAgo: Long): Boolean = Settings.autoBackup && Settings.autoBackupFolder.isNotBlank() && Settings.lastBackup < oneWeekAgo && !Citrine.isImportingEvents

    private fun scheduleEventCleanup(database: AppDatabase, nowMs: Long) {
        val runAgeRetention = lastAgeRetentionMs == 0L || (nowMs - lastAgeRetentionMs) >= AGE_RETENTION_INTERVAL_MS
        if (runAgeRetention) {
            lastAgeRetentionMs = nowMs
        }
        citrine.applicationScope.launch {
            citrine.eventCleanup.eventsToDelete(database, runAgeRetention = runAgeRetention)
        }
    }

    private fun scheduleLogTrim() {
        citrine.applicationScope.launch {
            try {
                LogDatabase.getDatabase(this@WebSocketServerService)
                    .logDao()
                    .deleteOlderThan(System.currentTimeMillis() - ONE_WEEK_MILLIS)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(Citrine.TAG, "Error deleting old logs", e)
            }
        }
    }

    private fun startAutoBackup(database: AppDatabase) {
        Citrine.isImportingEvents = true
        try {
            val folder = DocumentFile.fromTreeUri(this@WebSocketServerService, Settings.autoBackupFolder.toUri())
            folder?.let {
                Log.d(Citrine.TAG, "Backing up database")

                Settings.lastBackup = TimeUtils.now()
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, citrine)

                Citrine.job?.cancel()
                Citrine.job = citrine.applicationScope.launch(Dispatchers.IO) {
                    ExportDatabaseUtils.exportDatabase(
                        database = database,
                        context = this@WebSocketServerService,
                        folder = it,
                        deleteOldFiles = true,
                        onProgress = { progress -> showBackupProgress(progress) },
                    )
                }
            }
            Citrine.isImportingEvents = false
        } catch (e: Exception) {
            Citrine.isImportingEvents = false
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "Error backing up database", e)
        }
    }

    private fun showBackupProgress(progress: String) {
        val notificationManager = NotificationManagerCompat.from(citrine)
        if (progress.isBlank()) {
            notificationManager.cancel(BACKUP_PROGRESS_NOTIFICATION_ID)
            lastBackupProgressText = null
            Citrine.hasProgressNotification = false
        } else if (progress != lastBackupProgressText) {
            val channel = NotificationChannelCompat.Builder(
                "citrine",
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
                .setName("Citrine")
                .build()

            notificationManager.createNotificationChannel(channel)

            val copyIntent = Intent(citrine, ClipboardReceiver::class.java)
            copyIntent.putExtra("job", "cancel")

            val copyPendingIntent = PendingIntent.getBroadcast(
                citrine,
                0,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            val notification = NotificationCompat.Builder(citrine, "citrine")
                .setContentTitle("Citrine")
                .setContentText(progress)
                .setSmallIcon(R.drawable.ic_notification)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_launcher_background, citrine.getString(R.string.cancel), copyPendingIntent)
                .build()

            if (ActivityCompat.checkSelfPermission(citrine, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(BACKUP_PROGRESS_NOTIFICATION_ID, notification)
                lastBackupProgressText = progress
                Citrine.hasProgressNotification = true
            }
        }
    }

    private fun scheduleNsiteUpdateCheck() {
        citrine.applicationScope.launch {
            try {
                val updates = NsiteManager.checkForUpdates()
                updates.forEach { update ->
                    if (update.nsite.autoUpdate) {
                        NsiteManager.applyUpdate(update)
                    } else {
                        postNsiteUpdateNotification(update.nsite.address, update.nsite.displayName)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Error checking nsite updates", e)
            }
        }
    }

    private fun watchDatabaseUpgrade() {
        scope.launch {
            AppDatabase.isDatabaseUpgrading.collect { isUpgrading ->
                val notificationManager = NotificationManagerCompat.from(this@WebSocketServerService)
                if (isUpgrading) {
                    val channel = NotificationChannelCompat.Builder(
                        "citrine",
                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                    )
                        .setName("Citrine")
                        .build()
                    notificationManager.createNotificationChannel(channel)

                    val notification = NotificationCompat.Builder(this@WebSocketServerService, "citrine")
                        .setContentTitle(getString(R.string.app_name_release))
                        .setContentText(getString(R.string.upgrading_database))
                        .setSmallIcon(R.drawable.ic_notification)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build()

                    if (ActivityCompat.checkSelfPermission(this@WebSocketServerService, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        notificationManager.notify(DATABASE_UPGRADE_NOTIFICATION_ID, notification)
                    }
                } else {
                    notificationManager.cancel(DATABASE_UPGRADE_NOTIFICATION_ID)
                }
            }
        }
    }

    private fun startRelayServer(database: AppDatabase) {
        Log.d(Citrine.TAG, "Starting WebSocket server")
        val app = citrine
        CustomWebSocketService.server = CustomWebSocketServer(
            host = Settings.host,
            port = Settings.port,
            appDatabase = database,
            context = app,
            appScope = app.applicationScope,
            onEphemeralEventStored = { app.eventCleanup.scheduleEphemeralCleanup(database) },
        )
        CustomWebSocketService.server?.start()
    }

    @Suppress("NoComments")
    private fun startOrStopTor() {
        if (Settings.useTor || Settings.useProxy) {
            Log.d(Citrine.TAG, "Starting embedded Tor (hiddenService=${Settings.useTor}, socks=${Settings.useProxy})")
            TorManager.start(Settings.port, enableHiddenService = Settings.useTor)
        } else {
            // Tor is disabled: tear down any running client immediately so Arti's
            // background tasks and guard channels don't keep burning battery.
            TorManager.stop(destroyClient = true)
        }
    }

    private fun scheduleDuplicateTagCleanup(database: AppDatabase) {
        scope.launch {
            try {
                TagDeduplicator.cleanDatabases(database, this@WebSocketServerService)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Duplicate tag cleanup failed", e)
            }
        }
    }

    private fun startForegroundWithRetries() {
        var error = true
        var attempts = 0
        while (error && attempts < 5) {
            try {
                val initial = buildForegroundNotification(RelayAggregator.status.value.takeIf { it.enabled })
                startForeground(FOREGROUND_NOTIFICATION_ID, initial.notification)
                lastForegroundSignature = initial.signature
                error = false
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Error starting foreground service attempt $attempts", e)
            }
            attempts++
        }
    }

    private fun watchForegroundNotificationSources() {
        refreshNotificationOnAggregatorStatus()
        refreshNotificationWhenTorResolvesOnionHostname()
    }

    // The aggregator publishes status on every burst of received events and every
    // relay connect/disconnect. Updating the foreground-service notification that
    // often wakes SystemUI and racks up binder IPCs. Emit phase/enabled transitions
    // immediately and throttle counter-only refreshes to [NOTIFICATION_THROTTLE_MS].
    // Always read the latest value so a delayed sample() emission cannot
    // overwrite a newer phase/enabled transition with stale state.
    @Suppress("NoComments")
    @OptIn(FlowPreview::class)
    private fun refreshNotificationOnAggregatorStatus() {
        scope.launch {
            merge(
                RelayAggregator.status.distinctUntilChangedBy { it.enabled to it.phase },
                RelayAggregator.status.sample(NOTIFICATION_THROTTLE_MS),
            ).collect {
                try {
                    postForegroundNotificationIfChanged(RelayAggregator.status.value.takeIf { it.enabled })
                } catch (e: Exception) {
                    Log.e(Citrine.TAG, "Error updating aggregator notification", e)
                }
            }
        }
    }

    private fun refreshNotificationWhenTorResolvesOnionHostname() {
        scope.launch {
            TorManager.state.collect {
                try {
                    postForegroundNotificationIfChanged(RelayAggregator.status.value.takeIf { it.enabled })
                } catch (e: Exception) {
                    Log.e(Citrine.TAG, "Error updating Tor notification", e)
                }
            }
        }
    }

    /**
     * Stops Tor first, outside the timed shutdown block: that only enqueues the Tor stop on the
     * application scope, and it must always run so the SOCKS listener and the onion service go
     * away even if the server shutdown times out.
     */
    override fun onDestroy() {
        TorManager.stop()
        runBlocking {
            withTimeoutOrNull(5_000) {
                try {
                    timer?.cancel()
                    timer = null
                    RelayAggregator.stop()
                    EventSubscription.closeAll()
                    CustomWebSocketService.server?.stop()
                    CustomWebSocketService.server = null
                } catch (e: Throwable) {
                    Log.e(Citrine.TAG, "Error during service onDestroy cleanup", e)
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private data class ForegroundNotification(val notification: Notification, val signature: String)

    private fun buildForegroundNotification(status: AggregatorStatus? = null): ForegroundNotification {
        val groupId = "WebSocketServerServiceGroup"
        createForegroundChannel(groupId)

        val resultPendingIntent = mainActivityPendingIntent()

        val title = getString(R.string.relay_running_at_ws, Settings.host, Settings.port.toString())
        val notificationBuilder = NotificationCompat.Builder(this, "WebSocketServerServiceChannel")
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(resultPendingIntent)
            .setGroup(groupId)

        val localUrl = "ws://127.0.0.1:${Settings.port}"
        notificationBuilder.addAction(
            R.drawable.ic_launcher_background,
            getString(R.string.copy_local),
            copyPendingIntent(localUrl, requestCode = 1),
        )

        val wifiIp = NetworkUtils.getLocalIpAddress()
        val wifiUrl = if (!wifiIp.isNullOrBlank()) "ws://$wifiIp:${Settings.port}" else null
        if (wifiUrl != null) {
            notificationBuilder.addAction(
                R.drawable.ic_launcher_background,
                getString(R.string.copy_wifi),
                copyPendingIntent(wifiUrl, requestCode = 2),
            )
        }

        val onionHost = onionHostForNotification()
        val torUrl = if (!onionHost.isNullOrBlank()) "ws://$onionHost" else null
        if (torUrl != null) {
            notificationBuilder.addAction(
                R.drawable.ic_launcher_background,
                getString(R.string.copy_tor),
                copyPendingIntent(torUrl, requestCode = 3),
            )
        }

        val summary: String
        val details: String
        if (status != null && status.enabled) {
            summary = buildAggregatorSummary(status)
            details = buildAggregatorDetails(status)
            notificationBuilder
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
        } else {
            summary = ""
            details = ""
        }

        val signature = listOf(title, summary, details, localUrl, wifiUrl.orEmpty(), torUrl.orEmpty())
            .joinToString("")
        return ForegroundNotification(notificationBuilder.build(), signature)
    }

    private fun createForegroundChannel(groupId: String) {
        val notificationManager = NotificationManagerCompat.from(this)
        val channelId = "WebSocketServerServiceChannel"
        val group = NotificationChannelGroupCompat.Builder(groupId)
            .setName("WebSocket Server")
            .build()
        notificationManager.createNotificationChannelGroup(group)

        val channel = NotificationChannelCompat.Builder(channelId, NotificationManager.IMPORTANCE_DEFAULT)
            .setName("WebSocket Server")
            .setGroup(groupId)
            .setSound(null, null)
            .build()
        notificationManager.createNotificationChannel(channel)
    }

    private fun mainActivityPendingIntent(): PendingIntent? {
        val resultIntent = Intent(this, MainActivity::class.java)

        return TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(resultIntent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private fun onionHostForNotification(): String? = when (val torState = TorManager.state.value) {
        is TorManager.State.Running -> torState.hostname.takeIf { it.isNotBlank() }
        else -> Settings.onionHostname.takeIf { it.isNotBlank() && Settings.useTor }
    }

    private fun postForegroundNotificationIfChanged(status: AggregatorStatus?) {
        val built = buildForegroundNotification(status)
        if (built.signature == lastForegroundSignature) return
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(this).notify(FOREGROUND_NOTIFICATION_ID, built.notification)
        lastForegroundSignature = built.signature
    }

    // requestCode is used to disambiguate each PendingIntent's extras, otherwise
    // FLAG_UPDATE_CURRENT would have them all share the most recently set URL.
    @Suppress("NoComments")
    private fun copyPendingIntent(url: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ClipboardReceiver::class.java)
        intent.putExtra("url", url)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun postNsiteUpdateNotification(address: String, displayName: String) {
        val notificationManager = NotificationManagerCompat.from(citrine)
        if (ActivityCompat.checkSelfPermission(citrine, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val channel = NotificationChannelCompat.Builder(
            "citrine",
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName("Citrine")
            .build()
        notificationManager.createNotificationChannel(channel)

        val updateIntent = Intent(this, ClipboardReceiver::class.java)
        updateIntent.putExtra("nsite_update", address)
        val requestCodeUniquePerAddress = address.hashCode()
        val updatePendingIntent = PendingIntent.getBroadcast(
            this,
            requestCodeUniquePerAddress,
            updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(citrine, "citrine")
            .setContentTitle(getString(R.string.app_name_release))
            .setContentText(getString(R.string.nsite_update_available_notification, displayName))
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_launcher_background, getString(R.string.update_now), updatePendingIntent)
            .build()

        notificationManager.notify(NSITE_UPDATE_NOTIFICATION_ID, notification)
    }

    private fun buildAggregatorSummary(status: AggregatorStatus): String {
        val phase = when (status.phase) {
            AggregatorPhase.IDLE -> getString(R.string.relay_aggregator_phase_idle)
            AggregatorPhase.BOOTSTRAPPING -> getString(R.string.relay_aggregator_phase_bootstrapping)
            AggregatorPhase.REFRESHING -> getString(R.string.relay_aggregator_phase_refreshing)
            AggregatorPhase.LISTENING -> getString(R.string.relay_aggregator_phase_listening)
            AggregatorPhase.PAUSED -> getString(R.string.relay_aggregator_phase_paused)
        }
        val counts = getString(
            R.string.relay_aggregator_counts,
            status.authors,
            status.relaysConnected,
            status.relaysConfigured,
        )
        return "$phase · $counts"
    }

    private fun buildAggregatorDetails(status: AggregatorStatus): String {
        val phase = when (status.phase) {
            AggregatorPhase.IDLE -> getString(R.string.relay_aggregator_phase_idle)
            AggregatorPhase.BOOTSTRAPPING -> getString(R.string.relay_aggregator_phase_bootstrapping)
            AggregatorPhase.REFRESHING -> getString(R.string.relay_aggregator_phase_refreshing)
            AggregatorPhase.LISTENING -> getString(R.string.relay_aggregator_phase_listening)
            AggregatorPhase.PAUSED -> getString(R.string.relay_aggregator_phase_paused)
        }
        val counts = getString(
            R.string.relay_aggregator_counts,
            status.authors,
            status.relaysConnected,
            status.relaysConfigured,
        )
        val events = getString(R.string.relay_aggregator_events_received, status.eventsReceived)
        val lastRefresh = if (status.lastRefreshEpoch <= 0L) {
            getString(R.string.relay_aggregator_never_refreshed)
        } else {
            val ageSeconds = TimeUtils.now() - status.lastRefreshEpoch
            getString(R.string.relay_aggregator_last_refresh, formatAge(ageSeconds))
        }
        return listOf(phase, counts, events, lastRefresh).joinToString("\n")
    }

    private fun formatAge(ageSeconds: Long): String = when {
        ageSeconds < 0L -> "0s"
        ageSeconds < 60L -> "${ageSeconds}s"
        ageSeconds < 3600L -> "${ageSeconds / 60L}m"
        ageSeconds < 86_400L -> "${ageSeconds / 3600L}h"
        else -> "${ageSeconds / 86_400L}d"
    }

    companion object {
        /** Starts the relay service through an alarm, which a boot or package-replaced broadcast is allowed to do. */
        fun scheduleStart(context: Context) {
            try {
                val operation = PendingIntent.getForegroundService(
                    context,
                    10,
                    Intent(context, WebSocketServerService::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
                alarmManager.set(AlarmManager.RTC_WAKEUP, 1000, operation)
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Failed to start WebSocketServerService", e)
            }
        }
    }
}
