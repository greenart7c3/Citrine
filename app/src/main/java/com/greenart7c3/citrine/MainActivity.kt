package com.greenart7c3.citrine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anggrayudi.storage.SimpleStorageHelper
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openInputStream
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.ui.dialogs.ContactsDialog
import com.greenart7c3.citrine.ui.theme.CitrineTheme
import com.vitorpamplona.quartz.events.Event
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private var countByKind: Flow<List<EventDao.CountResult>>? = null
    private val storageHelper = SimpleStorageHelper(this@MainActivity)

    @OptIn(DelicateCoroutinesApi::class)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            start()
        }
    }

    private var service: WebSocketServerService? = null
    private var bound = false
    private var isLoading = mutableStateOf(true)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebSocketServerService.LocalBinder
            this@MainActivity.service = binder.getService()
            bound = true
            this@MainActivity.isLoading.value = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            this@MainActivity.isLoading.value = true
        }
    }

    private suspend fun stop() {
        isLoading.value = true
        val intent = Intent(applicationContext, WebSocketServerService::class.java)
        stopService(intent)
        unbindService(connection)
        bound = false
        service = null
        delay(1000)
        isLoading.value = false
    }

    private suspend fun start() {
        isLoading.value = true
        val intent = Intent(applicationContext, WebSocketServerService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        delay(1000)
        isLoading.value = false
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        storageHelper.onFolderSelected = { _, folder ->
            GlobalScope.launch(Dispatchers.IO) {
                val events = database.eventDao().getAll()
                val json = events.joinToString(separator = "\n") {
                    it.toEvent().toJson()
                }

                val file = folder.makeFile(this@MainActivity, "citrine.jsonl")
                val op = file?.openOutputStream(this@MainActivity)
                op?.writer().use {
                    it?.write(json)
                }
            }
        }

        storageHelper.onFileSelected = { _, files ->
            GlobalScope.launch(Dispatchers.IO) {
                val json = files.first().openInputStream(this@MainActivity)?.bufferedReader().use {
                    it?.readText()
                }

                database.eventDao().deleteAll()

                json!!.split("\n").map {
                    it.trim()
                }.filter {
                    it.isNotEmpty()
                }.map {
                    Event.fromJson(it).toEventWithTags()
                }.forEach {
                    database.eventDao().insertEventWithTags(it, false)
                }
            }
        }

        super.onCreate(savedInstanceState)

        requestPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")

        setContent {
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current
            var pubKey by remember {
                mutableStateOf("")
            }
            database = AppDatabase.getDatabase(this)

            val launcherLogin = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
                onResult = { result ->
                    if (result.resultCode != RESULT_OK) {
                        Toast.makeText(
                            context,
                            getString(R.string.sign_request_rejected),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        result.data?.let {
                            pubKey = it.getStringExtra("signature") ?: ""
                        }
                    }
                },
            )

            CitrineTheme {
                if (countByKind == null) {
                    countByKind = database.eventDao().countByKind()
                }
                val flow = countByKind?.collectAsState(initial = listOf())
                val count = EventSubscription.subscriptionCount.collectAsState(0)

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (pubKey.isNotBlank()) {
                        ContactsDialog(pubKey = pubKey) {
                            pubKey = ""
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (isLoading.value) {
                            CircularProgressIndicator()
                        } else {
                            val isStarted = service?.isStarted() ?: false
                            if (isStarted) {
                                Text(stringResource(R.string.relay_started_at))
                                Text("ws://localhost:${service?.port() ?: 0}")
                                ElevatedButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            isLoading.value = true
                                            stop()
                                            delay(1000)
                                            isLoading.value = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.stop))
                                }
                            } else {
                                Text(stringResource(R.string.relay_not_running))
                                ElevatedButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            isLoading.value = true
                                            start()
                                            delay(1000)
                                            isLoading.value = false
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.start))
                                }
                            }

                            ElevatedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
                                        val signerType = "get_public_key"
                                        intent.putExtra("type", signerType)
                                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        launcherLogin.launch(intent)
                                    } catch (e: Exception) {
                                        Log.d("intent", e.message ?: "", e)
                                        coroutineScope.launch(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                getString(R.string.no_external_signer_installed),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/greenart7c3/Amber/releases"))
                                        launcherLogin.launch(intent)
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.restore_follows))
                            }

                            Row {
                                ElevatedButton(
                                    onClick = {
                                        storageHelper.openFolderPicker()
                                    },
                                ) {
                                    Text("Export database")
                                }
                                Spacer(modifier = Modifier.padding(4.dp))
                                ElevatedButton(
                                    onClick = {
                                        storageHelper.openFilePicker()
                                    },
                                ) {
                                    Text("Import database")
                                }
                            }

                            Spacer(modifier = Modifier.padding(4.dp))
                            Text(
                                "Relay",
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Connections: ${service?.webSocketServer?.connections?.size ?: 0}")
                            Text("Subscriptions: ${count.value}")
                            Spacer(modifier = Modifier.padding(4.dp))
                            Text("Database", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.padding(4.dp))

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Kind", fontWeight = FontWeight.Bold)
                                Text("Count", fontWeight = FontWeight.Bold)
                            }

                            flow?.value?.forEach { item ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("${item.kind}")
                                    Text("${item.count}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
