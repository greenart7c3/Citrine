package com.greenart7c3.citrine.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackBuilder
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
import com.greenart7c3.citrine.database.AppDatabase
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
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val NOTIFICATION_THROTTLE_MS = 5_000L
private const val ONE_WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
private const val ONE_DAY_SECONDS = 24L * 60 * 60

// Notification id for "nsite update available" prompts. Ids 1/2/3 are already used by the
// foreground service, backup/download progress, and database-upgrade notifications.
const val NSITE_UPDATE_NOTIFICATION_ID = 4

class WebSocketServerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()
    private var timer: Timer? = null

    // Signature of the last notification we posted to id 1, used to skip re-posting
    // identical content. Re-posting an unchanged notification can still wake the
    // device and refresh the system UI, even with setOnlyAlertOnce.
    @Volatile private var lastForegroundSignature: String? = null

    // Signature of the last backup-progress notification we posted to id 2.
    @Volatile private var lastBackupProgressText: String? = null
    inner class LocalBinder : Binder() {
        fun getService(): WebSocketServerService = this@WebSocketServerService
    }

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
                    if ((Citrine.job == null || Citrine.job?.isCompleted == true) && !Citrine.isImportingEvents) {
                        NotificationManagerCompat.from(Citrine.instance).cancel(2)
                        lastBackupProgressText = null
                    }

                    if (!Citrine.isImportingEvents) {
                        Citrine.instance.applicationScope.launch {
                            Citrine.instance.eventsToDelete(database)
                        }
                    }

                    Citrine.instance.applicationScope.launch {
                        try {
                            LogDatabase.getDatabase(this@WebSocketServerService)
                                .logDao()
                                .deleteOlderThan(System.currentTimeMillis() - ONE_WEEK_MILLIS)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(Citrine.TAG, "Error deleting old logs", e)
                        }
                    }

                    if (Settings.autoBackup && Settings.autoBackupFolder.isNotBlank() && !Citrine.isImportingEvents) {
                        Citrine.isImportingEvents = true
                        try {
                            val folder = DocumentFile.fromTreeUri(this@WebSocketServerService, Settings.autoBackupFolder.toUri())
                            folder?.let {
                                val oneWeekAgo = TimeUtils.oneWeekAgo()
                                if (Settings.lastBackup < oneWeekAgo) {
                                    Log.d(Citrine.TAG, "Backing up database")

                                    Settings.lastBackup = TimeUtils.now()
                                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)

                                    Citrine.job?.cancel()
                                    Citrine.job = Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                                        ExportDatabaseUtils.exportDatabase(
                                            database = database,
                                            context = this@WebSocketServerService,
                                            folder = it,
                                            deleteOldFiles = true,
                                            onProgress = {
                                                val notificationManager = NotificationManagerCompat.from(Citrine.instance)
                                                if (it.isBlank()) {
                                                    notificationManager.cancel(2)
                                                    lastBackupProgressText = null
                                                } else if (it != lastBackupProgressText) {
                                                    val channel = NotificationChannelCompat.Builder(
                                                        "citrine",
                                                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                                                    )
                                                        .setName("Citrine")
                                                        .build()

                                                    notificationManager.createNotificationChannel(channel)

                                                    val copyIntent = Intent(Citrine.instance, ClipboardReceiver::class.java)
                                                    copyIntent.putExtra("job", "cancel")

                                                    val copyPendingIntent = PendingIntent.getBroadcast(
                                                        Citrine.instance,
                                                        0,
                                                        copyIntent,
                                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                                                    )

                                                    val notification = NotificationCompat.Builder(Citrine.instance, "citrine")
                                                        .setContentTitle("Citrine")
                                                        .setContentText(it)
                                                        .setSmallIcon(R.drawable.ic_notification)
                                                        .setOnlyAlertOnce(true)
                                                        .addAction(R.drawable.ic_launcher_background, Citrine.instance.getString(R.string.cancel), copyPendingIntent)
                                                        .build()

                                                    if (ActivityCompat.checkSelfPermission(Citrine.instance, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                                        notificationManager.notify(2, notification)
                                                        lastBackupProgressText = it
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            Citrine.isImportingEvents = false
                        } catch (e: Exception) {
                            Citrine.isImportingEvents = false
                            if (e is CancellationException) throw e
                            Log.e(Citrine.TAG, "Error backing up database", e)
                        }
                    }

                    // Daily nsite update check. Gated by lastNsiteCheck like the weekly backup,
                    // and persisted before the async work so the next 100s tick doesn't re-trigger.
                    if (!Citrine.isImportingEvents && Settings.nsites.isNotEmpty()) {
                        val oneDayAgo = TimeUtils.now() - ONE_DAY_SECONDS
                        if (Settings.lastNsiteCheck < oneDayAgo) {
                            Settings.lastNsiteCheck = TimeUtils.now()
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.instance)
                            Citrine.instance.applicationScope.launch {
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
                    }
                }
            },
            0,
            100000,
        )

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
                        notificationManager.notify(3, notification)
                    }
                } else {
                    notificationManager.cancel(3)
                }
            }
        }

        Log.d(Citrine.TAG, "Starting WebSocket server")
        // Start the WebSocket server
        CustomWebSocketService.server = CustomWebSocketServer(
            host = Settings.host,
            port = Settings.port,
            appDatabase = database,
        )
        CustomWebSocketService.server?.start()

        if (Settings.useTor || Settings.useProxy) {
            Log.d(Citrine.TAG, "Starting embedded Tor (hiddenService=${Settings.useTor}, socks=${Settings.useProxy})")
            TorManager.start(Settings.port, enableHiddenService = Settings.useTor)
        } else {
            // Tor may still be running from before a settings change if onDestroy's
            // timed cleanup was cut short.
            TorManager.stop()
        }

        if (Settings.relayAggregatorEnabled) {
            RelayAggregator.start(database)
        }

        var error = true
        var attempts = 0
        while (error && attempts < 5) {
            try {
                val initial = buildForegroundNotification(RelayAggregator.status.value.takeIf { it.enabled })
                startForeground(1, initial.notification)
                lastForegroundSignature = initial.signature
                error = false
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Error starting foreground service attempt $attempts", e)
            }
            attempts++
        }

        @OptIn(FlowPreview::class)
        scope.launch {
            // The aggregator publishes status on every received event and every relay
            // connect/disconnect, so the StateFlow can fire dozens of times per second.
            // Updating the foreground-service notification that often racks up wakelocks
            // and thrashes the system UI. Sample so the notification is rewritten at
            // most once per [NOTIFICATION_THROTTLE_MS].
            RelayAggregator.status
                .sample(NOTIFICATION_THROTTLE_MS)
                .collect { status ->
                    try {
                        postForegroundNotificationIfChanged(status.takeIf { it.enabled })
                    } catch (e: Exception) {
                        Log.e(Citrine.TAG, "Error updating aggregator notification", e)
                    }
                }
        }

        scope.launch {
            // Refresh the notification when Tor finishes bootstrapping so the Copy Tor
            // action appears with the resolved onion hostname.
            TorManager.state.collect {
                try {
                    postForegroundNotificationIfChanged(RelayAggregator.status.value.takeIf { it.enabled })
                } catch (e: Exception) {
                    Log.e(Citrine.TAG, "Error updating Tor notification", e)
                }
            }
        }
    }

    override fun onDestroy() {
        // Outside the timed block below: this only enqueues the daemon stop on the
        // application scope, and it must always run so the Tor foreground service
        // (and its notification) goes away even if the server shutdown times out.
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

    private fun createNotification(status: AggregatorStatus? = null): Notification = buildForegroundNotification(status).notification

    private fun buildForegroundNotification(status: AggregatorStatus? = null): ForegroundNotification {
        val notificationManager = NotificationManagerCompat.from(this)
        val channelId = "WebSocketServerServiceChannel"
        val groupId = "WebSocketServerServiceGroup"
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

        val resultIntent = Intent(this, MainActivity::class.java)

        val resultPendingIntent = TaskStackBuilder.create(this).run {
            // Add the intent, which inflates the back stack.
            addNextIntentWithParentStack(resultIntent)
            // Get the PendingIntent containing the entire back stack.
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val title = getString(R.string.relay_running_at_ws, Settings.host, Settings.port.toString())
        val notificationBuilder = NotificationCompat.Builder(this, "WebSocketServerServiceChannel")
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(resultPendingIntent)
            .setGroup(groupId)

        // requestCode is used to disambiguate each PendingIntent's extras, otherwise
        // FLAG_UPDATE_CURRENT would have them all share the most recently set URL.
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

        val torState = TorManager.state.value
        val onionHost = when (torState) {
            is TorManager.State.Running -> torState.hostname.takeIf { it.isNotBlank() }
            else -> Settings.onionHostname.takeIf { it.isNotBlank() && Settings.useTor }
        }
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
        NotificationManagerCompat.from(this).notify(1, built.notification)
        lastForegroundSignature = built.signature
    }

    fun isStarted(): Boolean = CustomWebSocketService.server != null

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
        val notificationManager = NotificationManagerCompat.from(Citrine.instance)
        if (ActivityCompat.checkSelfPermission(Citrine.instance, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
        val updatePendingIntent = PendingIntent.getBroadcast(
            this,
            // Unique requestCode per address so concurrent nsite notifications don't collide.
            address.hashCode(),
            updateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(Citrine.instance, "citrine")
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
}
