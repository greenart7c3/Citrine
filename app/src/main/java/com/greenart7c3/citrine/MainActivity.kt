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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anggrayudi.storage.SimpleStorageHelper
import com.anggrayudi.storage.file.extension
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openInputStream
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.server.EventSubscription
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.WebSocketServerService
import com.greenart7c3.citrine.ui.LogcatScreen
import com.greenart7c3.citrine.ui.SettingsScreen
import com.greenart7c3.citrine.ui.components.DatabaseButtons
import com.greenart7c3.citrine.ui.components.DatabaseInfo
import com.greenart7c3.citrine.ui.components.RelayInfo
import com.greenart7c3.citrine.ui.dialogs.ContactsDialog
import com.greenart7c3.citrine.ui.navigation.Route
import com.greenart7c3.citrine.ui.theme.CitrineTheme
import com.vitorpamplona.quartz.events.Event
import kotlinx.coroutines.CancellationException
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
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    private suspend fun stop() {
        try {
            isLoading.value = true
            val intent = Intent(applicationContext, WebSocketServerService::class.java)
            stopService(intent)
            if (bound) unbindService(connection)
            bound = false
            service = null
            delay(2000)
        } finally {
            isLoading.value = false
        }
    }

    private suspend fun start() {
        try {
            isLoading.value = true
            val intent = Intent(applicationContext, WebSocketServerService::class.java)
            startService(intent)
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            delay(2000)
        } finally {
            isLoading.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val progress = mutableStateOf("")
        super.onCreate(savedInstanceState)

        requestPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")

        setContent {
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current
            var pubKey by remember {
                mutableStateOf("")
            }
            val progress2 = remember {
                progress
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

            val items = listOf(Route.Home, Route.Settings)

            CitrineTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val destinationRoute = navBackStackEntry?.destination?.route ?: ""

                Scaffold(
                    bottomBar = {
                        if (destinationRoute != Route.Logs.route) {
                            NavigationBar(tonalElevation = 0.dp) {
                                items.forEach {
                                    val selected = destinationRoute == it.route
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(it.route) {
                                                popUpTo(0)
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                if (selected) it.selectedIcon else it.icon,
                                                it.route,
                                            )
                                        },
                                        label = {
                                            Text(it.route)
                                        },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = Route.Home.route,
                    ) {
                        composable(Route.Logs.route) {
                            LogcatScreen(
                                onClose = {
                                    navController.navigateUp()
                                },
                            )
                        }

                        composable(Route.Home.route) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .verticalScroll(rememberScrollState()),
                                color = MaterialTheme.colorScheme.background,
                            ) {
                                var showDialog by remember { mutableStateOf(false) }
                                val selectedFiles = remember {
                                    mutableListOf<DocumentFile>()
                                }

                                storageHelper.onFolderSelected = { _, folder ->
                                    exportDatabase(
                                        folder = folder,
                                        onProgress = {
                                            progress.value = it
                                        },
                                    )
                                }

                                storageHelper.onFileSelected = { _, files ->
                                    selectedFiles.clear()
                                    selectedFiles.addAll(files)
                                    showDialog = true
                                }

                                if (showDialog) {
                                    AlertDialog(
                                        onDismissRequest = {
                                            showDialog = false
                                        },
                                        title = {
                                            Text(getString(R.string.import_events))
                                        },
                                        text = {
                                            Text(getString(R.string.import_events_warning))
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    showDialog = false
                                                    importDatabase(
                                                        files = selectedFiles,
                                                        shouldDelete = true,
                                                        onProgress = {
                                                            progress.value = it
                                                        },
                                                        onFinished = {
                                                            selectedFiles.clear()
                                                        },
                                                    )
                                                },
                                            ) {
                                                Text(getString(R.string.yes))
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(
                                                onClick = {
                                                    showDialog = false
                                                    importDatabase(
                                                        files = selectedFiles,
                                                        shouldDelete = false,
                                                        onProgress = {
                                                            progress.value = it
                                                        },
                                                        onFinished = {
                                                            selectedFiles.clear()
                                                        },
                                                    )
                                                },
                                            ) {
                                                Text(getString(R.string.no))
                                            }
                                        },
                                    )
                                }

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
                                        if (progress2.value.isNotBlank()) {
                                            Spacer(modifier = Modifier.padding(4.dp))
                                            Text(progress2.value)
                                        }
                                    } else {
                                        val isStarted = service?.isStarted() ?: false
                                        val clipboardManager = LocalClipboardManager.current
                                        if (isStarted) {
                                            Text(stringResource(R.string.relay_started_at))
                                            Text(
                                                "ws://${Settings.host}:${Settings.port}",
                                                modifier = Modifier.clickable {
                                                    clipboardManager.setText(AnnotatedString("ws://${Settings.host}:${Settings.port}"))
                                                },
                                            )
                                            ElevatedButton(
                                                onClick = {
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        stop()
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
                                                    Log.d(Citrine.TAG, e.message ?: "", e)
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

                                        DatabaseButtons(
                                            onExport = {
                                                storageHelper.openFolderPicker()
                                            },
                                            onImport = {
                                                storageHelper.openFilePicker()
                                            },
                                        )

                                        ElevatedButton(
                                            onClick = {
                                                navController.navigate(Route.Logs.route)
                                            },
                                        ) {
                                            Text(stringResource(R.string.logs))
                                        }

                                        Spacer(modifier = Modifier.padding(4.dp))

                                        if (countByKind == null) {
                                            countByKind = database.eventDao().countByKind()
                                        }
                                        val flow = countByKind?.collectAsStateWithLifecycle(initialValue = listOf())
                                        val count = EventSubscription.subscriptionCount.collectAsStateWithLifecycle(0)

                                        RelayInfo(
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            connections = service?.webSocketServer?.connections?.size ?: 0,
                                            subscriptions = count.value,
                                        )
                                        Spacer(modifier = Modifier.padding(4.dp))
                                        DatabaseInfo(
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            flow = flow,
                                        )
                                    }
                                }
                            }
                        }

                        composable(Route.Settings.route) {
                            SettingsScreen(
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(16.dp),
                                onApplyChanges = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        stop()
                                        delay(1000)
                                        start()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun exportDatabase(folder: DocumentFile, onProgress: (String) -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                val file = folder.makeFile(this@MainActivity, "citrine.jsonl")
                val op = file?.openOutputStream(this@MainActivity)
                op?.writer().use { writer ->
                    val events = database.eventDao().getAllIds()
                    events.forEachIndexed { index, it ->
                        val event = database.eventDao().getById(it)!!
                        val json = event.toEvent().toJson() + "\n"
                        writer?.write(json)
                        onProgress("Exported ${index + 1}/${events.size}")
                    }
                }
            } finally {
                onProgress("")
                isLoading.value = false
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun importDatabase(files: List<DocumentFile>, shouldDelete: Boolean, onProgress: (String) -> Unit, onFinished: () -> Unit) {
        GlobalScope.launch(Dispatchers.IO) {
            val file = files.first()
            if (file.extension != "jsonl") {
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.invalid_file_extension),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }

            try {
                isLoading.value = true
                var totalLines = 0
                onProgress("Reading file ${file.name}")
                val input = file.openInputStream(this@MainActivity) ?: return@launch
                input.use { ip ->
                    ip.bufferedReader().use {
                        var line: String?
                        while (it.readLine().also { readLine -> line = readLine } != null) {
                            if (line?.isNotBlank() == true) {
                                totalLines++
                            }
                        }
                    }
                }
                val input2 = file.openInputStream(this@MainActivity) ?: return@launch
                input2.use { ip ->
                    ip.bufferedReader().use {
                        var linesRead = 0

                        if (shouldDelete) {
                            onProgress("deleting all events")
                            database.eventDao().deleteAll()
                        }

                        it.useLines { lines ->
                            lines.forEach { line ->
                                if (line.isBlank()) {
                                    return@forEach
                                }
                                val event = Event.fromJson(line)
                                val eventWithTags = event.toEventWithTags()
                                database.eventDao().insertEventWithTags(eventWithTags, false)
                                linesRead++
                                onProgress("Imported $linesRead/$totalLines")
                            }
                        }
                    }
                }

                onProgress("")
                isLoading.value = false
                onFinished()
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.imported_events_successfully, totalLines),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.import_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                onProgress("")
                isLoading.value = false
                onFinished()
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
