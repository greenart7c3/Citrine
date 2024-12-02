package com.greenart7c3.citrine.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.MainActivity
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WebSocketServerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()
    private var timer: Timer? = null
    inner class LocalBinder : Binder() {
        fun getService(): WebSocketServerService = this@WebSocketServerService
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun eventsToDelete(database: AppDatabase) {
        GlobalScope.launch(Dispatchers.IO) {
            if (Settings.deleteEphemeralEvents) {
                val ephemeralEvents = database.eventDao().getEphemeralEvents()
                Log.d(Citrine.TAG, "Deleting ${ephemeralEvents.size} ephemeral events")
                database.eventDao().delete(ephemeralEvents.map { it.event.id })
            }

            if (Settings.deleteExpiredEvents) {
                val expiredEvents = database.eventDao().getEventsWithExpirations().mapNotNull {
                    val event = it.toEvent()
                    if (event.isExpired()) {
                        it
                    } else {
                        null
                    }
                }
                Log.d(Citrine.TAG, "Deleting ${expiredEvents.size} expired events")
                database.eventDao().delete(expiredEvents.map { it.event.id })
            }

            if (Settings.deleteEventsOlderThan != OlderThan.NEVER) {
                val until = when (Settings.deleteEventsOlderThan) {
                    OlderThan.DAY -> TimeUtils.oneDayAgo()
                    OlderThan.WEEK -> TimeUtils.oneWeekAgo()
                    OlderThan.MONTH -> TimeUtils.now() - TimeUtils.ONE_MONTH
                    OlderThan.YEAR -> TimeUtils.now() - TimeUtils.ONE_YEAR
                    else -> 0
                }
                if (until > 0) {
                    val oldEvents = EventRepository.query(
                        database,
                        EventFilter(
                            until = until.toInt(),
                        ),
                    )
                    if (Settings.neverDeleteFrom.isNotEmpty()) {
                        val neverDeleteFrom = Settings.neverDeleteFrom
                        val filteredOldEvents = oldEvents.filter { it.event.pubkey !in neverDeleteFrom }
                        Log.d(Citrine.TAG, "Deleting ${filteredOldEvents.size} old events (older than ${Settings.deleteEventsOlderThan})")
                        database.eventDao().delete(filteredOldEvents.map { it.event.id })
                    } else {
                        Log.d(Citrine.TAG, "Deleting ${oldEvents.size} old events (older than ${Settings.deleteEventsOlderThan})")
                        database.eventDao().delete(oldEvents.map { it.event.id })
                    }
                }
            }
        }
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
                    eventsToDelete(database)

                    if (Settings.autoBackup && Settings.autoBackupFolder.isNotBlank()) {
                        try {
                            val folder = DocumentFile.fromTreeUri(this@WebSocketServerService, Settings.autoBackupFolder.toUri())
                            folder?.let {
                                val lastModifiedTime = folder.lastModified()
                                val currentTime = System.currentTimeMillis()
                                val twentyFourHoursAgo = currentTime - (24 * 60 * 60 * 1000)
                                val fiveDaysAgo = currentTime - (5 * 24 * 60 * 60 * 1000)

                                if (lastModifiedTime < twentyFourHoursAgo) {
                                    Log.d(Citrine.TAG, "Deleting old backups")
                                    folder.listFiles().forEach { file ->
                                        if (file.lastModified() < fiveDaysAgo) {
                                            file.delete()
                                        }
                                    }

                                    Log.d(Citrine.TAG, "Backing up database")
                                    ExportDatabaseUtils.exportDatabase(
                                        database,
                                        this@WebSocketServerService,
                                        it,
                                        onProgress = {},
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(Citrine.TAG, "Error backing up database", e)
                        }
                    }
                }
            },
            0,
            300000,
        )

        Log.d(Citrine.TAG, "Starting WebSocket server")
        // Start the WebSocket server
        CustomWebSocketService.server = CustomWebSocketServer(
            host = Settings.host,
            port = Settings.port,
            appDatabase = database,
        )
        CustomWebSocketService.server?.start()

        var error = true
        var attempts = 0
        while (error && attempts < 5) {
            try {
                startForeground(1, createNotification())
                error = false
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Error starting foreground service attempt $attempts", e)
            }
            attempts++
        }
    }

    override fun onDestroy() {
        scope.launch(Dispatchers.IO) {
            timer?.cancel()
            timer = null
            EventSubscription.closeAll()
            CustomWebSocketService.server?.stop()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotification(): Notification {
        Log.d(Citrine.TAG, "Creating notification")
        val channelId = "WebSocketServerServiceChannel"
        val channel = NotificationChannel(channelId, "WebSocket Server", NotificationManager.IMPORTANCE_DEFAULT)
        channel.setSound(null, null)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val copyIntent = Intent(this, ClipboardReceiver::class.java)
        copyIntent.putExtra("url", "ws://${Settings.host}:${Settings.port}")

        val copyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val resultIntent = Intent(this, MainActivity::class.java)

        val resultPendingIntent = TaskStackBuilder.create(this).run {
            // Add the intent, which inflates the back stack.
            addNextIntentWithParentStack(resultIntent)
            // Get the PendingIntent containing the entire back stack.
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notificationBuilder = NotificationCompat.Builder(this, "WebSocketServerServiceChannel")
            .setContentTitle("Relay running at ws://${Settings.host}:${Settings.port}")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_launcher_background, "Copy Address", copyPendingIntent)
            .setContentIntent(resultPendingIntent)

        return notificationBuilder.build()
    }

    fun isStarted(): Boolean {
        return CustomWebSocketService.server != null
    }
}
