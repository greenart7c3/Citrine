package com.greenart7c3.citrine.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TaskStackBuilder
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.getSystemService
import com.greenart7c3.citrine.MainActivity
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.server.EventSubscription
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

    private val brCopy: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val clipboard = getSystemService(
                applicationContext,
                ClipboardManager::class.java,
            ) as ClipboardManager
            val clip = ClipData.newPlainText("WebSocket Server Address", "ws://localhost:${webSocketServer.port()}")
            clipboard.setPrimaryClip(clip)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun eventsToDelete(database: AppDatabase) {
        GlobalScope.launch(Dispatchers.IO) {
            val ephemeralEvents = EventRepository.query(
                database,
                EventFilter(
                    kinds = (20000 until 30000).toSet(),
                ),
            )
            Log.d("timer", "Deleting ${ephemeralEvents.size} ephemeral events")
            database.eventDao().delete(ephemeralEvents.map { it.event.id })

            val expiredEvents = database.eventDao().getEventsWithExpirations().mapNotNull {
                val event = it.toEvent()
                if (event.isExpired()) {
                    it
                } else {
                    null
                }
            }
            Log.d("timer", "Deleting ${expiredEvents.size} expired events")
            database.eventDao().delete(expiredEvents.map { it.event.id })
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

        val intentFilter = IntentFilter("com.example.ACTION_COPY")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(brCopy, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(brCopy, intentFilter)
        }

        // Start the WebSocket server
        webSocketServer = CustomWebSocketServer(
            DEFAULT_PORT,
            database,
        )
        webSocketServer.start()

        // Create a notification to keep the service in the foreground
        startForeground(1, createNotification())
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        EventSubscription.closeAll()
        unregisterReceiver(brCopy)
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

        val copy = Intent("com.example.ACTION_COPY")
        val piCopy = PendingIntent.getBroadcast(this, 0, copy, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val resultIntent = Intent(this, MainActivity::class.java)

        val resultPendingIntent = TaskStackBuilder.create(this).run {
            // Add the intent, which inflates the back stack.
            addNextIntentWithParentStack(resultIntent)
            // Get the PendingIntent containing the entire back stack.
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notificationBuilder = NotificationCompat.Builder(this, "WebSocketServerServiceChannel")
            .setContentTitle("Relay running at ws://localhost:${webSocketServer.port()}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_launcher_background, "Copy Address", piCopy)
            .setContentIntent(resultPendingIntent)

        return notificationBuilder.build()
    }

    fun isStarted(): Boolean {
        return webSocketServer.server != null
    }

    fun port(): Int? {
        return webSocketServer.port()
    }

    companion object {
        const val DEFAULT_PORT = 4869
    }
}
