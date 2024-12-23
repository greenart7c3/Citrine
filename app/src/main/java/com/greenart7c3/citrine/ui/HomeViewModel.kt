package com.greenart7c3.citrine.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import com.anggrayudi.storage.file.extension
import com.anggrayudi.storage.file.openInputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.utils.ExportDatabaseUtils
import com.vitorpamplona.ammolite.relays.RelayPool
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = false,
    val bound: Boolean = false,
    val service: WebSocketServerService? = null,
    val progress: String = "",
)

class HomeViewModel : ViewModel() {
    init {
        Log.d(Citrine.TAG, "HomeViewModel init")
    }

    private val _state = MutableStateFlow(HomeState())
    val state = _state

    fun loadEventsFromPubKey(pubKey: String) {
        if (pubKey.isNotBlank()) {
            Citrine.getInstance().applicationScope.launch {
                if (Citrine.getInstance().isImportingEvents) return@launch
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(Citrine.getInstance(), "Check your notifications for progress", Toast.LENGTH_SHORT).show()
                }
                Citrine.getInstance().isImportingEvents = true
                Citrine.getInstance().job?.join()
                Citrine.getInstance().job = Citrine.getInstance().applicationScope.launch {
                    Citrine.getInstance().getAllEventsFromPubKey()
                }
            }
        }
    }

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

    fun exportDatabase(
        folder: DocumentFile,
        context: Context,
    ) {
        Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
            setLoading(true)

            try {
                ExportDatabaseUtils.exportDatabase(
                    context,
                    folder,
                ) {
                    _state.value = _state.value.copy(
                        progress = it,
                    )
                }
            } finally {
                _state.value = _state.value.copy(
                    progress = "",
                    loading = false,
                )
            }
        }
    }

    fun importDatabase(
        files: List<DocumentFile>,
        shouldDelete: Boolean,
        context: Context,
        onFinished: () -> Unit,
    ) {
        Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
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
                Citrine.getInstance().isImportingEvents = true
                state.value = state.value.copy(
                    loading = true,
                    progress = context.getString(R.string.reading_file, file.name),
                )

//                input.use { ip ->
//                    ip.bufferedReader().use {
//                        var line: String?
//                        while (it.readLine().also { readLine -> line = readLine } != null) {
//                            if (line?.isNotBlank() == true) {
//                                totalLines++
//                            }
//                        }
//                    }
//                }
                var linesRead = 0
                val input2 = file.openInputStream(context) ?: return@launch
                input2.use { ip ->
                    ip.bufferedReader().use {
                        if (shouldDelete) {
                            _state.value = _state.value.copy(
                                progress = context.getString(R.string.deleting_all_events),
                            )
                            AppDatabase.getNostrDatabase().wipe()
                        }

                        it.useLines { lines ->
                            lines.forEach { line ->
                                if (line.isBlank()) {
                                    return@forEach
                                }
                                val event = rust.nostr.sdk.Event.fromJson(line)
                                CustomWebSocketService.server?.innerProcessEvent(event, null)

                                linesRead++
                                _state.value = _state.value.copy(
                                    progress = context.getString(R.string.imported_events, linesRead.toString()),
                                )
                            }
                        }
                        RelayPool.disconnect()
                        delay(3000)
                        RelayPool.unloadRelays()
                    }
                }

                _state.value = _state.value.copy(
                    progress = "",
                    loading = false,
                )
                Citrine.getInstance().isImportingEvents = false
                onFinished()
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.imported_events_successfully, linesRead),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Citrine.getInstance().isImportingEvents = false
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                Citrine.getInstance().applicationScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.import_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                _state.value = _state.value.copy(
                    progress = "",
                    loading = false,
                )
                onFinished()
            }
        }
    }

    override fun onCleared() {
        Log.d(Citrine.TAG, "HomeViewModel cleared")
        if (state.value.bound) {
            Citrine.getInstance().unbindService(connection)
            _state.value = _state.value.copy(
                bound = false,
            )
        }
        super.onCleared()
    }

    fun setProgress(message: String) {
        _state.value = _state.value.copy(
            progress = message,
        )
    }
}
