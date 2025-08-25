package com.greenart7c3.citrine.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import com.anggrayudi.storage.file.extension
import com.anggrayudi.storage.file.openInputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.service.ClipboardReceiver
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = false,
    val bound: Boolean = false,
    val service: WebSocketServerService? = null,
    val pubKey: String = "",
)

class HomeViewModel : ViewModel() {
    init {
        Log.d(Citrine.TAG, "HomeViewModel init")
    }

    private val _state = MutableStateFlow(HomeState())
    val state = _state
    var signer = NostrSignerExternal(
        "",
        "",
        Citrine.getInstance().contentResolver,
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketServerService.LocalBinder
            _state.value = _state.value.copy(
                service = binder.getService(),
                bound = true,
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _state.value = _state.value.copy(
                bound = false,
            )
        }
    }

    fun setLoading(loading: Boolean) {
        _state.value = _state.value.copy(loading = loading)
    }

    suspend fun stop(context: Context) {
        try {
            setLoading(true)
            val intent = Intent(context, WebSocketServerService::class.java)
            context.stopService(intent)
            if (state.value.bound) context.unbindService(connection)
            _state.value = _state.value.copy(
                service = null,
                bound = false,
            )
            delay(2000)
        } catch (e: Exception) {
            setLoading(false)
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.message ?: "", e)
        } finally {
            setLoading(false)
        }
    }

    suspend fun start(context: Context) {
        if (state.value.service?.isStarted() == true) {
            return
        }

        try {
            setLoading(true)
            val intent = Intent(context, WebSocketServerService::class.java)
            context.startService(intent)
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            delay(2000)
        } catch (e: Exception) {
            setLoading(false)
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.message ?: "", e)
        } finally {
            setLoading(false)
        }
    }

    fun setPubKey(returnedKey: String) {
        _state.value = _state.value.copy(
            pubKey = returnedKey,
        )
    }

    fun exportDatabase(
        folder: DocumentFile,
        database: AppDatabase,
        context: Context,
    ) {
        Citrine.getInstance().cancelJob()
        Citrine.job = Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            ExportDatabaseUtils.exportDatabase(
                database,
                context,
                folder,
            ) {
                setProgress(it)
            }
        }
    }

    fun importDatabase(
        files: List<DocumentFile>,
        shouldDelete: Boolean,
        database: AppDatabase,
        context: Context,
        onFinished: () -> Unit,
    ) {
        Citrine.getInstance().cancelJob()
        Citrine.job = Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            val file = files.first()
            if (file.extension != "jsonl") {
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.invalid_file_extension),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }

            try {
                Citrine.isImportingEvents = true
                setProgress(context.getString(R.string.reading_file, file.name))

                var linesRead = 0
                val input2 = file.openInputStream(context) ?: return@launch
                input2.use { ip ->
                    ip.bufferedReader().use {
                        if (shouldDelete) {
                            setProgress(context.getString(R.string.deleting_all_events))
                            database.eventDao().deleteAll()
                        }

                        it.useLines { lines ->
                            lines.forEach { line ->
                                if (line.isBlank()) {
                                    return@forEach
                                }
                                val event = Event.fromJson(line)
                                CustomWebSocketService.server?.innerProcessEvent(event, null)

                                linesRead++
                                if (linesRead % 100 == 0) {
                                    setProgress(context.getString(R.string.imported2, linesRead.toString()))
                                }
                            }
                        }
                        delay(3000)
                    }
                }

                setProgress(context.getString(R.string.imported_events_successfully, linesRead))
                Citrine.isImportingEvents = false
                onFinished()
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.imported_events_successfully, linesRead),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Citrine.isImportingEvents = false
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                setProgress("")
                setProgress(context.getString(R.string.import_failed))
                onFinished()
            }
        }
    }

    override fun onCleared() {
        try {
            Log.d(Citrine.TAG, "HomeViewModel cleared")
            if (state.value.bound) {
                Citrine.getInstance().unbindService(connection)
                _state.value = _state.value.copy(
                    bound = false,
                )
            }
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.message ?: "", e)
        }
        super.onCleared()
    }

    fun setProgress(message: String) {
        val notificationManager = NotificationManagerCompat.from(Citrine.getInstance())

        if (message.isBlank()) {
            notificationManager.cancel(2)
            return
        }

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
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_launcher_background, Citrine.getInstance().getString(R.string.cancel), copyPendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(Citrine.getInstance(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationManager.notify(2, notification)
    }
}
