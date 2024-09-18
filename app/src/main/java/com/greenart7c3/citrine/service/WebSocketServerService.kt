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
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class WebSocketServerService : Service() {
    lateinit var webSocketServer: CustomWebSocketServer
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

        val database = AppDatabase.getDatabase(this@WebSocketServerService)

        timer?.cancel()
        timer = Timer()
        timer?.schedule(
            object : TimerTask() {
                override fun run() {
                    eventsToDelete(database)
                }
            },
            0,
            300000,
        )

        // Start the WebSocket server
        webSocketServer = CustomWebSocketServer(
            host = Settings.host,
            port = Settings.port,
            appDatabase = database,
        )
        webSocketServer.start()

        // Create a notification to keep the service in the foreground
        startForeground(1, createNotification())
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        EventSubscription.closeAll()
        webSocketServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun createNotification(): Notification {
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
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_launcher_background, "Copy Address", copyPendingIntent)
            .setContentIntent(resultPendingIntent)

        return notificationBuilder.build()
    }

    fun isStarted(): Boolean {
        return webSocketServer.server != null
    }
}
