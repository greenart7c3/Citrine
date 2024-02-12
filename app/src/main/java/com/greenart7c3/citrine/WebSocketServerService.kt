package com.greenart7c3.citrine

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat.getSystemService
import com.greenart7c3.citrine.database.AppDatabase

class WebSocketServerService : Service() {
    lateinit var webSocketServer: CustomWebSocketServer
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WebSocketServerService = this@WebSocketServerService
    }

    private val brCopy: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val clipboard = getSystemService(
                applicationContext,
                ClipboardManager::class.java
            ) as ClipboardManager
            val clip = ClipData.newPlainText("WebSocket Server Address", "ws://localhost:${webSocketServer.port()}")
            clipboard.setPrimaryClip(clip)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val intentFilter = IntentFilter("com.example.ACTION_COPY")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(brCopy, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(brCopy, intentFilter)
        }

        // Start the WebSocket server
        val port = if (BuildConfig.DEBUG) defaultPortDebug else defaultPort
        webSocketServer = CustomWebSocketServer(
            port,
            AppDatabase.getDatabase(this@WebSocketServerService)
        )
        webSocketServer.start()

        // Create a notification to keep the service in the foreground
        startForeground(1, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(brCopy)
        // Stop the WebSocket server when the service is destroyed
        webSocketServer.stop()
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

    companion object {
        const val defaultPort = 7777
        const val defaultPortDebug = 7778
    }
}
