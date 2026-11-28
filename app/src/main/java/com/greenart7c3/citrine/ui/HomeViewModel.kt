package com.greenart7c3.citrine.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anggrayudi.storage.file.extension
import com.anggrayudi.storage.file.openInputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.ClipboardReceiver
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HomeState(
    val pubKey: String = "",
)

class HomeViewModel(
    private val appContext: Context,
    private val appScope: CoroutineScope,
) : ViewModel() {
    init {
        Log.d(Citrine.TAG, "HomeViewModel init")
    }

    private val _state = MutableStateFlow(HomeState())
    val state = _state
    private var signer = NostrSignerExternal(
        "",
        "",
        appContext.contentResolver,
    )

    val isRunning = CustomWebSocketService.running

    fun registerSignerLauncher(launcher: (Intent) -> Unit) {
        signer.registerForegroundLauncher(launcher)
    }

    fun onSignerResponse(data: Intent) {
        signer.newResponse(data)
    }

    fun loginWithExternalSigner(key: String, packageName: String): String {
        val returnedKey = if (key.startsWith("npub")) {
            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                is NPub -> parsed.hex
                else -> ""
            }
        } else {
            key
        }

        signer = NostrSignerExternal(
            returnedKey,
            packageName,
            appContext.contentResolver,
        )

        setPubKey(returnedKey)
        return returnedKey
    }

    suspend fun stop(context: Context) {
        try {
            val intent = Intent(context, WebSocketServerService::class.java)
            context.stopService(intent)
            withTimeoutOrNull(5_000) {
                CustomWebSocketService.running.first { !it }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.message ?: "", e)
        }
    }

    suspend fun start(context: Context) {
        if (CustomWebSocketService.server != null) {
            return
        }

        try {
            val intent = Intent(context, WebSocketServerService::class.java)
            context.startService(intent)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(Citrine.TAG, e.message ?: "", e)
        }
    }

    /**
     * Wipes every table once the running background job has stopped. The aggregator is
     * stopped first so its listener cannot write into tables being cleared, and restarted
     * afterwards with its sync marker reset, since the cache it would reuse is gone.
     */
    fun deleteAllEvents() {
        Citrine.cancelJob()
        appScope.launch(Dispatchers.IO) {
            Citrine.job?.join()
            val resumeAggregator = Settings.relayAggregatorEnabled
            if (resumeAggregator) RelayAggregator.stop()
            Citrine.isImportingEvents = true
            setProgress("Deleting all events")
            database.clearAllTables()
            setProgress("")
            Citrine.isImportingEvents = false
            if (resumeAggregator) {
                Settings.relayAggregatorLastSync = 0L
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
                RelayAggregator.start(database)
            }
        }
    }

    fun setPubKey(returnedKey: String) {
        _state.value = _state.value.copy(
            pubKey = returnedKey,
        )
    }

    private val database by lazy { AppDatabase.getDatabase(appContext) }

    /** True while Room migrates the database on first open after an upgrade. */
    val isDatabaseUpgrading = AppDatabase.isDatabaseUpgrading

    fun exportDatabase(
        folder: DocumentFile,
        context: Context,
    ) {
        Citrine.cancelJob()
        Citrine.job = appScope.launch(Dispatchers.IO) {
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
        context: Context,
        onFinished: () -> Unit,
    ) {
        Citrine.cancelJob()
        Citrine.job = appScope.launch(Dispatchers.IO) {
            val file = files.first()
            if (file.extension != "jsonl") {
                appScope.launch(Dispatchers.Main) {
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
                                val result = CustomWebSocketService.server?.innerProcessEvent(event, null)
                                if (result == CustomWebSocketServer.VerificationResult.InvalidId || result == CustomWebSocketServer.VerificationResult.InvalidSignature) {
                                    Log.d(Citrine.TAG, "before: ${event.tags.size}")

                                    val uniqueTags: Array<Array<String>> = event.tags
                                        .distinctBy { tag -> tag.joinToString("|") }
                                        .map { value -> value }
                                        .toTypedArray()
                                    Log.d(Citrine.TAG, "after: ${uniqueTags.size}")

                                    val newEvent = Event(
                                        id = event.id,
                                        pubKey = event.pubKey,
                                        createdAt = event.createdAt,
                                        kind = event.kind,
                                        tags = uniqueTags,
                                        content = event.content,
                                        sig = event.sig,
                                    )

                                    val result = CustomWebSocketService.server?.innerProcessEvent(newEvent, null)
                                    Log.d(Citrine.TAG, "result: $result")
                                }

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
                appScope.launch(Dispatchers.Main) {
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
                appScope.launch(Dispatchers.Main) {
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
        Log.d(Citrine.TAG, "HomeViewModel cleared")
    }

    companion object {
        fun factory(appContext: Context, appScope: CoroutineScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(appContext, appScope) }
        }
    }

    fun setProgress(message: String) {
        val notificationManager = NotificationManagerCompat.from(appContext)

        if (message.isBlank()) {
            notificationManager.cancel(2)
            Citrine.hasProgressNotification = false
            return
        }

        val channel = NotificationChannelCompat.Builder(
            "citrine",
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName("Citrine")
            .build()

        notificationManager.createNotificationChannel(channel)

        val copyIntent = Intent(appContext, ClipboardReceiver::class.java)
        copyIntent.putExtra("job", "cancel")

        val copyPendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, "citrine")
            .setContentTitle("Citrine")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_launcher_background, appContext.getString(R.string.cancel), copyPendingIntent)
            .build()

        if (ActivityCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationManager.notify(2, notification)
        Citrine.hasProgressNotification = true
    }
}
