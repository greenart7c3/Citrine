package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.RelayInfo
import com.greenart7c3.citrine.ui.dialogs.DeleteAllDialog
import com.greenart7c3.citrine.ui.dialogs.ImportEventsDialog
import com.greenart7c3.citrine.ui.navigation.Route
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip55AndroidSigner.ExternalSignerLauncher
import com.vitorpamplona.quartz.nip55AndroidSigner.NostrSignerExternal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    modifier: Modifier,
    homeViewModel: HomeViewModel,
    navController: NavController,
    storageHelper: SimpleStorageHelper,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        val requestPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
                homeViewModel.start(context)
            }
        }

        LaunchedEffect(Unit) {
            requestPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }

        val state = homeViewModel.state.collectAsStateWithLifecycle()
        var deleteAllDialog by remember { mutableStateOf(false) }
        var showDialog by remember { mutableStateOf(false) }
        var showAutoBackupDialog by remember { mutableStateOf(false) }
        val selectedFiles = remember {
            mutableListOf<DocumentFile>()
        }
        var saveToPreferences by remember { mutableStateOf(false) }

        val database = AppDatabase.getDatabase(context)
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = { result ->
                if (result.resultCode != RESULT_OK) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.sign_request_rejected),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    result.data?.let {
                        coroutineScope.launch(Dispatchers.IO) {
                            homeViewModel.signer.launcher.newResult(it)
                        }
                    }
                }
            },
        )

        val lifeCycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifeCycleOwner) {
            val observer = LifecycleEventObserver { _, _ ->
                homeViewModel.signer.launcher.registerLauncher(
                    contentResolver = Citrine.getInstance()::contentResolverFn,
                    launcher = { intent ->
                        launcher.launch(intent)
                    },
                )
            }
            lifeCycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifeCycleOwner.lifecycle.removeObserver(observer)
            }
        }

        val launcherLoginContacts = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
            onResult = { result ->
                if (result.resultCode != RESULT_OK) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.sign_request_rejected),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    result.data?.let {
                        try {
                            val key = it.getStringExtra("signature") ?: ""
                            val packageName = it.getStringExtra("package") ?: ""

                            val returnedKey = if (key.startsWith("npub")) {
                                when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                                    is NPub -> parsed.hex
                                    else -> ""
                                }
                            } else {
                                key
                            }

                            homeViewModel.signer = NostrSignerExternal(
                                returnedKey,
                                ExternalSignerLauncher(returnedKey, packageName),
                            )

                            homeViewModel.setPubKey(returnedKey)
                            navController.navigate(Route.ContactsScreen.route.replace("{pubkey}", returnedKey))
                        } catch (e: Exception) {
                            Log.d(Citrine.TAG, e.message ?: "", e)
                        }
                    }
                }
            },
        )

        LaunchedEffect(Unit) {
            if (LocalPreferences.shouldShowAutoBackupDialog(context)) {
                showAutoBackupDialog = true
            }
        }

        storageHelper.onFolderSelected = { _, folder ->
            if (saveToPreferences) {
                Settings.autoBackup = true
                Settings.autoBackupFolder = folder.uri.toString()
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                saveToPreferences = false
            }
            homeViewModel.exportDatabase(
                folder = folder,
                database = database,
                context = context,
            )
        }

        storageHelper.onFileSelected = { _, files ->
            selectedFiles.clear()
            selectedFiles.addAll(files)
            showDialog = true
        }

        if (deleteAllDialog) {
            DeleteAllDialog(
                onClose = {
                    deleteAllDialog = false
                },
                onConfirm = {
                    deleteAllDialog = false
                    Citrine.getInstance().cancelJob()
                    Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
                        Citrine.job?.join()
                        Citrine.isImportingEvents = true
                        homeViewModel.setProgress("Deleting all events")
                        database.clearAllTables()
                        homeViewModel.setProgress("")
                        Citrine.isImportingEvents = false
                    }
                },
            )
        }

        if (showDialog) {
            ImportEventsDialog(
                onClose = {
                    showDialog = false
                },
                onConfirm = {
                    showDialog = false
                    homeViewModel.importDatabase(
                        files = selectedFiles,
                        shouldDelete = true,
                        context = context,
                        database = database,
                        onFinished = {
                            selectedFiles.clear()
                        },
                    )
                },
                onDismiss = {
                    showDialog = false
                    homeViewModel.importDatabase(
                        files = selectedFiles,
                        shouldDelete = false,
                        context = context,
                        database = database,
                        onFinished = {
                            selectedFiles.clear()
                        },
                    )
                },
            )
        }

        if (showAutoBackupDialog) {
            AlertDialog(
                onDismissRequest = {
                    showAutoBackupDialog = false
                },
                title = {
                    Text(stringResource(R.string.auto_backup))
                },
                text = {
                    Text(stringResource(R.string.select_a_folder_to_backup_to))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showAutoBackupDialog = false
                            saveToPreferences = true
                            storageHelper.openFolderPicker()
                        },
                    ) {
                        Text(stringResource(R.string.select_folder))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            Settings.autoBackup = false
                            Settings.autoBackupFolder = ""
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            showAutoBackupDialog = false
                        },
                    ) {
                        Text(stringResource(R.string.never_auto_backup))
                    }
                },
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.value.loading) {
                CircularProgressIndicator()
            } else {
                val isStarted = homeViewModel.state.value.service?.isStarted() ?: false
                val clipboardManager = LocalClipboard.current
                if (isStarted) {
                    Text(stringResource(R.string.relay_started_at))
                    Text(
                        "ws://${Settings.host}:${Settings.port}",
                        modifier = Modifier.clickable {
                            coroutineScope.launch {
                                clipboardManager.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("", "ws://${Settings.host}:${Settings.port}"),
                                    ),
                                )
                            }
                        },
                    )
                    ElevatedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                homeViewModel.stop(context)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.stop))
                    }
                } else {
                    Text(stringResource(R.string.relay_not_running))
                    ElevatedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                homeViewModel.start(context)
                            }
                        },
                        content = {
                            Text(stringResource(R.string.start))
                        },
                    )
                }

                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
                            val signerType = "get_public_key"
                            intent.putExtra("type", signerType)
                            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            launcherLoginContacts.launch(intent)
                        } catch (e: Exception) {
                            Log.d(Citrine.TAG, e.message ?: "", e)
                            coroutineScope.launch(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.no_external_signer_installed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
                            launcherLoginContacts.launch(intent)
                        }
                    },
                ) {
                    Text(stringResource(R.string.restore_follows))
                }

                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        saveToPreferences = false
                        storageHelper.openFolderPicker()
                    },
                ) {
                    Text(stringResource(R.string.export_database))
                }

                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        storageHelper.openFilePicker()
                    },
                ) {
                    Text(stringResource(R.string.import_database))
                }

                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navController.navigate(Route.DownloadYourEventsUserScreen.route)
                    },
                    content = {
                        Text(stringResource(R.string.download_your_events))
                    },
                )

                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navController.navigate(Route.Logs.route)
                    },
                ) {
                    Text(stringResource(R.string.logs))
                }

                Spacer(modifier = Modifier.padding(8.dp))

                ElevatedButton(
                    colors = ButtonDefaults.elevatedButtonColors().copy(
                        containerColor = Color.Red,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        deleteAllDialog = true
                    },
                    content = {
                        Text(stringResource(R.string.delete_all_events), color = Color.White)
                    },
                )

                Spacer(modifier = Modifier.padding(4.dp))

                val connectionFlow = CustomWebSocketService.server?.connections?.collectAsStateWithLifecycle(initialValue = listOf())
                var shouldShowConnections by remember { mutableStateOf(false) }

                if (shouldShowConnections) {
                    Dialog(
                        onDismissRequest = {
                            shouldShowConnections = false
                        },
                        properties = DialogProperties(
                            usePlatformDefaultWidth = false,
                        ),
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            LazyColumn(
                                Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                            ) {
                                item {
                                    Box(Modifier.padding(bottom = 8.dp)) {
                                        CloseButton(
                                            onCancel = { shouldShowConnections = false },
                                        )
                                    }
                                }
                                items(connectionFlow!!.value) {
                                    Text("${it.name} - ${it.remoteAddress()} - ${it.since.formatLongToCustomDateTimeWithSeconds()}")
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClick = {
                                if (connectionFlow?.value?.isNotEmpty() == true) {
                                    shouldShowConnections = true
                                }
                            },
                        ),
                ) {
                    RelayInfo(
                        modifier = Modifier
                            .fillMaxWidth(),
                        connections = connectionFlow?.value?.size ?: 0,
                    )
                }
                Spacer(modifier = Modifier.padding(4.dp))

                ElevatedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navController.navigate(Route.DatabaseInfo.route)
                    },
                ) {
                    Text(stringResource(R.string.show_events))
                }
            }
        }
    }
}

fun Long.formatLongToCustomDateTimeWithSeconds(): String {
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss - dd MMM")
    return dateTime.format(formatter)
}
