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
import android.util.Log
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
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.vitorpamplona.quartz.utils.TimeUtils
import java.util.Timer
import java.util.TimerTask
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WebSocketServerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()
    private var timer: Timer? = null
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
                        NotificationManagerCompat.from(Citrine.getInstance()).cancel(2)
                        Citrine.getInstance().applicationScope.launch {
                            Citrine.getInstance().client.getAll().forEach {
                                if (it.isConnected()) {
                                    it.disconnect()
                                }
                            }
                            if (Citrine.getInstance().client.getAll().isNotEmpty()) {
                                Citrine.getInstance().client.reconnect(relays = null)
                            }
                        }
                    }

                    if (!Citrine.isImportingEvents) {
                        Citrine.getInstance().applicationScope.launch {
                            Citrine.getInstance().eventsToDelete(database)
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
                                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, Citrine.getInstance())

                                    Citrine.job?.cancel()
                                    Citrine.job = Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
                                        ExportDatabaseUtils.exportDatabase(
                                            database = database,
                                            context = this@WebSocketServerService,
                                            folder = it,
                                            deleteOldFiles = true,
                                            onProgress = {
                                                val notificationManager = NotificationManagerCompat.from(Citrine.getInstance())
                                                if (it.isBlank()) {
                                                    notificationManager.cancel(2)
                                                } else {
                                                    val channel = NotificationChannelCompat.Builder(
                                                        "citrine",
                                                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                                                    )
                                                        .setName("Citrine")
                                                        .build()

                                                    notificationManager.createNotificationChannel(channel)

                                                    val copyIntent = Intent(Citrine.getInstance(), ClipboardReceiver::class.java)
                                                    copyIntent.putExtra("job", "cancel")

                                                    val copyPendingIntent = PendingIntent.getBroadcast(
                                                        Citrine.getInstance(),
                                                        0,
                                                        copyIntent,
                                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                                                    )

                                                    val notification = NotificationCompat.Builder(Citrine.getInstance(), "citrine")
                                                        .setContentTitle("Citrine")
                                                        .setContentText(it)
                                                        .setSmallIcon(R.drawable.ic_notification)
                                                        .setOnlyAlertOnce(true)
                                                        .addAction(R.drawable.ic_launcher_background, Citrine.getInstance().getString(R.string.cancel), copyPendingIntent)
                                                        .build()

                                                    if (ActivityCompat.checkSelfPermission(Citrine.getInstance(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                                        notificationManager.notify(2, notification)
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
                }
            },
            0,
            100000,
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

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotification(): Notification {
        Log.d(Citrine.TAG, "Creating notification")
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
            .setContentTitle(getString(R.string.relay_running_at_ws, Settings.host, Settings.port.toString()))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_launcher_background, getString(R.string.copy_address), copyPendingIntent)
            .setContentIntent(resultPendingIntent)
            .setGroup(groupId)

        return notificationBuilder.build()
    }

    fun isStarted(): Boolean = CustomWebSocketService.server != null
}
