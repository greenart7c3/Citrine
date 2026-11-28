package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Connection
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.ui.components.AddressRow
import com.greenart7c3.citrine.ui.components.AggregatorStatusCard
import com.greenart7c3.citrine.ui.components.RelayInfo
import com.greenart7c3.citrine.ui.dialogs.DeleteAllDialog
import com.greenart7c3.citrine.ui.dialogs.ImportEventsDialog
import com.greenart7c3.citrine.ui.navigation.Route
import com.greenart7c3.citrine.utils.NetworkUtils
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    navController: NavController,
    storageHelper: StoragePickerHelper,
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(LocalContext.current.applicationContext, LocalAppScope.current)),
) {
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    val coroutineScope = rememberCoroutineScope()
    val noSignerMsg = stringResource(R.string.no_external_signer_installed)

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        NotificationPermissionRequest(
            onResult = {
                appScope.launch(Dispatchers.IO) {
                    homeViewModel.start(context)
                }
            },
        )

        val state = homeViewModel.state.collectAsStateWithLifecycle()
        val isUpgradingDatabase = homeViewModel.isDatabaseUpgrading.collectAsStateWithLifecycle()
        val isRunning = homeViewModel.isRunning.collectAsStateWithLifecycle()

        SignerForegroundLauncher(
            onResponse = {
                coroutineScope.launch(Dispatchers.IO) {
                    homeViewModel.onSignerResponse(it)
                }
            },
            onRegister = homeViewModel::registerSignerLauncher,
        )

        val launcherLoginContacts = rememberLoginContactsLauncher { key, packageName ->
            val returnedKey = homeViewModel.loginWithExternalSigner(key, packageName)
            navController.navigate(Route.ContactsScreen.route.replace("{pubkey}", returnedKey).replace("{packageName}", packageName))
        }

        HomeContent(
            isUpgradingDatabase = isUpgradingDatabase.value,
            isRunning = isRunning.value,
            homeState = state.value,
            storageHelper = storageHelper,
            relayActions = RelayActions(
                onStart = { coroutineScope.launch(Dispatchers.IO) { homeViewModel.start(context) } },
                onStop = { coroutineScope.launch(Dispatchers.IO) { homeViewModel.stop(context) } },
                onRetryTor = { coroutineScope.launch(Dispatchers.IO) { com.greenart7c3.citrine.service.TorManager.retry() } },
            ),
            databaseActions = DatabaseActions(
                onExport = { folder -> homeViewModel.exportDatabase(folder = folder, context = context) },
                onImport = { files, shouldDelete, onFinished ->
                    homeViewModel.importDatabase(files = files, shouldDelete = shouldDelete, context = context, onFinished = onFinished)
                },
                onDeleteAll = homeViewModel::deleteAllEvents,
            ),
            onRestoreFollows = { launchSignerLogin(launcherLoginContacts, context, coroutineScope, noSignerMsg) },
            onNavigate = { route -> navController.navigate(route) },
        )
    }
}

private class RelayActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryTor: () -> Unit,
)

private class DatabaseActions(
    val onExport: (DocumentFile) -> Unit,
    val onImport: (files: List<DocumentFile>, shouldDelete: Boolean, onFinished: () -> Unit) -> Unit,
    val onDeleteAll: () -> Unit,
)

@Stable
private class HomeDialogState {
    var deleteAllDialog by mutableStateOf(false)
    var showDialog by mutableStateOf(false)
    var showAutoBackupDialog by mutableStateOf(false)
    val selectedFiles = mutableListOf<DocumentFile>()
    var saveToPreferences by mutableStateOf(false)
}

@Composable
private fun NotificationPermissionRequest(onResult: () -> Unit) {
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        onResult()
    }

    LaunchedEffect(Unit) {
        requestPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
    }
}

@Composable
private fun SignerForegroundLauncher(
    onResponse: (Intent) -> Unit,
    onRegister: ((Intent) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(
                    context,
                    signRequestRejectedMsg,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                result.data?.let {
                    onResponse(it)
                }
            }
        },
    )

    val currentOnRegister by rememberUpdatedState(onRegister)
    val lifeCycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifeCycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            currentOnRegister { intent ->
                launcher.launch(intent)
            }
        }
        lifeCycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifeCycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun rememberLoginContactsLauncher(
    onLogin: (key: String, packageName: String) -> Unit,
): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    return rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(
                    context,
                    signRequestRejectedMsg,
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                result.data?.let {
                    try {
                        val key = it.getStringExtra("signature") ?: ""
                        val packageName = it.getStringExtra("package") ?: ""

                        onLogin(key, packageName)
                    } catch (e: Exception) {
                        Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        },
    )
}

private fun launchSignerLogin(
    launcherLoginContacts: ManagedActivityResultLauncher<Intent, ActivityResult>,
    context: Context,
    coroutineScope: CoroutineScope,
    noSignerMsg: String,
) {
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
                noSignerMsg,
                Toast.LENGTH_SHORT,
            ).show()
        }
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
        launcherLoginContacts.launch(intent)
    }
}

@Composable
private fun HomeContent(
    isUpgradingDatabase: Boolean,
    isRunning: Boolean,
    homeState: HomeState,
    storageHelper: StoragePickerHelper,
    relayActions: RelayActions,
    databaseActions: DatabaseActions,
    onRestoreFollows: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val dialogs = remember { HomeDialogState() }

    LaunchedEffect(Unit) {
        if (LocalPreferences.shouldShowAutoBackupDialog(context)) {
            dialogs.showAutoBackupDialog = true
        }
    }

    storageHelper.onFolderSelected = { _, folder ->
        if (dialogs.saveToPreferences) {
            Settings.autoBackup = true
            Settings.autoBackupFolder = folder.uri.toString()
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
            dialogs.saveToPreferences = false
        }
        databaseActions.onExport(folder)
    }

    storageHelper.onFileSelected = { _, files ->
        dialogs.selectedFiles.clear()
        dialogs.selectedFiles.addAll(files)
        dialogs.showDialog = true
    }

    HomeDialogs(
        dialogs = dialogs,
        storageHelper = storageHelper,
        onImport = databaseActions.onImport,
        onDeleteAll = databaseActions.onDeleteAll,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isUpgradingDatabase) {
            CircularProgressIndicator()
            Text(stringResource(R.string.upgrading_database))
        } else {
            HomeSections(
                isRunning = isRunning,
                homeState = homeState,
                dialogs = dialogs,
                storageHelper = storageHelper,
                relayActions = relayActions,
                onRestoreFollows = onRestoreFollows,
                onNavigate = onNavigate,
            )
        }
    }
}

@Composable
private fun HomeDialogs(
    dialogs: HomeDialogState,
    storageHelper: StoragePickerHelper,
    onImport: (files: List<DocumentFile>, shouldDelete: Boolean, onFinished: () -> Unit) -> Unit,
    onDeleteAll: () -> Unit,
) {
    val context = LocalContext.current

    if (dialogs.deleteAllDialog) {
        DeleteAllDialog(
            onClose = {
                dialogs.deleteAllDialog = false
            },
            onConfirm = {
                dialogs.deleteAllDialog = false
                onDeleteAll()
            },
        )
    }

    if (dialogs.showDialog) {
        ImportEventsDialog(
            onClose = {
                dialogs.showDialog = false
            },
            onConfirm = {
                dialogs.showDialog = false
                onImport(dialogs.selectedFiles, true) {
                    dialogs.selectedFiles.clear()
                }
            },
            onDismiss = {
                dialogs.showDialog = false
                onImport(dialogs.selectedFiles, false) {
                    dialogs.selectedFiles.clear()
                }
            },
        )
    }

    if (dialogs.showAutoBackupDialog) {
        AutoBackupDialog(
            onDismissRequest = {
                dialogs.showAutoBackupDialog = false
            },
            onSelectFolder = {
                dialogs.showAutoBackupDialog = false
                dialogs.saveToPreferences = true
                storageHelper.openFolderPicker()
            },
            onNeverAutoBackup = {
                Settings.autoBackup = false
                Settings.autoBackupFolder = ""
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                dialogs.showAutoBackupDialog = false
            },
        )
    }
}

@Composable
private fun AutoBackupDialog(
    onDismissRequest: () -> Unit,
    onSelectFolder: () -> Unit,
    onNeverAutoBackup: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.auto_backup))
        },
        text = {
            Text(stringResource(R.string.select_a_folder_to_backup_to))
        },
        confirmButton = {
            TextButton(
                onClick = onSelectFolder,
            ) {
                Text(stringResource(R.string.select_folder))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onNeverAutoBackup,
            ) {
                Text(stringResource(R.string.never_auto_backup))
            }
        },
    )
}

@Composable
private fun ColumnScope.HomeSections(
    isRunning: Boolean,
    homeState: HomeState,
    dialogs: HomeDialogState,
    storageHelper: StoragePickerHelper,
    relayActions: RelayActions,
    onRestoreFollows: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    if (isRunning) {
        RunningRelaySection(
            homeState = homeState,
            port = Settings.port,
            useTor = Settings.useTor,
            onStop = relayActions.onStop,
            onRetryTor = relayActions.onRetryTor,
        )
    } else {
        StoppedRelaySection(onStart = relayActions.onStart)
    }

    if (Settings.relayAggregatorEnabled) {
        AggregatorSection()
    }

    DatabaseButtons(
        onRestoreFollows = onRestoreFollows,
        onExport = {
            dialogs.saveToPreferences = false
            storageHelper.openFolderPicker()
        },
        onImport = {
            storageHelper.openFilePicker()
        },
    )

    ToolButtons(
        onNavigate = onNavigate,
        onDeleteAll = {
            dialogs.deleteAllDialog = true
        },
    )

    val connectionFlow = CustomWebSocketService.server?.connections?.collectAsStateWithLifecycle(initialValue = listOf())
    ConnectionsSection(connections = connectionFlow?.value)

    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onNavigate(Route.DatabaseInfo.route)
        },
    ) {
        Text(stringResource(R.string.show_events))
    }
}

@Composable
private fun ColumnScope.RunningRelaySection(
    homeState: HomeState,
    port: Int,
    useTor: Boolean,
    onStop: () -> Unit,
    onRetryTor: () -> Unit,
) {
    Text(stringResource(R.string.relay_started_at))
    AddressRow(
        label = stringResource(R.string.address_local),
        address = "ws://127.0.0.1:$port",
    )
    val wifiIp = remember(homeState) { NetworkUtils.getLocalIpAddress() }
    if (!wifiIp.isNullOrBlank()) {
        AddressRow(
            label = stringResource(R.string.address_wifi),
            address = "ws://$wifiIp:$port",
        )
    }
    if (useTor) {
        TorAddress(
            onRetryTor = onRetryTor,
        )
    }
    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStop,
    ) {
        Text(stringResource(R.string.stop))
    }
}

@Composable
private fun ColumnScope.TorAddress(
    onRetryTor: () -> Unit,
) {
    val torState by com.greenart7c3.citrine.service.TorManager.state.collectAsStateWithLifecycle()
    when (val s = torState) {
        com.greenart7c3.citrine.service.TorManager.State.Off -> {
            if (Settings.onionHostname.isNotBlank()) {
                AddressRow(
                    label = stringResource(R.string.address_tor),
                    address = "ws://${Settings.onionHostname}",
                    color = Color.Gray,
                )
            }
        }
        com.greenart7c3.citrine.service.TorManager.State.Bootstrapping -> {
            Text(stringResource(R.string.tor_starting))
        }
        is com.greenart7c3.citrine.service.TorManager.State.Running -> {
            if (s.hostname.isNotBlank()) {
                AddressRow(
                    label = stringResource(R.string.address_tor),
                    address = "ws://${s.hostname}",
                )
            }
        }
        is com.greenart7c3.citrine.service.TorManager.State.Error -> {
            Text(
                stringResource(R.string.tor_error, s.message),
                color = Color.Red,
            )
            ElevatedButton(
                onClick = onRetryTor,
            ) {
                Text(stringResource(R.string.tor_retry))
            }
        }
    }
}

@Composable
private fun ColumnScope.StoppedRelaySection(onStart: () -> Unit) {
    Text(stringResource(R.string.relay_not_running))
    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onStart,
        content = {
            Text(stringResource(R.string.start))
        },
    )
}

@Composable
private fun AggregatorSection() {
    @OptIn(FlowPreview::class)
    val aggregatorStatus = remember { RelayAggregator.status.sample(2_000) }
        .collectAsStateWithLifecycle(initialValue = remember { RelayAggregator.status.value })
    AggregatorStatusCard(
        status = aggregatorStatus.value,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.DatabaseButtons(
    onRestoreFollows: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onRestoreFollows,
    ) {
        Text(stringResource(R.string.restore_follows))
    }

    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onExport,
    ) {
        Text(stringResource(R.string.export_database))
    }

    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onImport,
    ) {
        Text(stringResource(R.string.import_database))
    }
}

@Composable
private fun ColumnScope.ToolButtons(
    onNavigate: (String) -> Unit,
    onDeleteAll: () -> Unit,
) {
    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            // TODO: Replace this with a setup screen to allow automatic download in background
            onNavigate(Route.DownloadYourEventsUserScreen.route)
        },
        content = {
            Text(stringResource(R.string.download_your_events))
        },
    )

    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onNavigate(Route.Rebroadcast.route)
        },
        content = {
            Text(stringResource(R.string.rebroadcast_events))
        },
    )

    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            onNavigate(Route.Logs.route)
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
        onClick = onDeleteAll,
        content = {
            Text(stringResource(R.string.delete_all_events), color = Color.White)
        },
    )

    Spacer(modifier = Modifier.padding(4.dp))
}

@Composable
private fun ColumnScope.ConnectionsSection(connections: List<Connection>?) {
    var shouldShowConnections by remember { mutableStateOf(false) }

    if (shouldShowConnections) {
        ConnectionsDialog(
            connections = connections!!,
            onDismiss = {
                shouldShowConnections = false
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = {
                    if (connections?.isNotEmpty() == true) {
                        shouldShowConnections = true
                    }
                },
            ),
    ) {
        RelayInfo(
            modifier = Modifier
                .fillMaxWidth(),
            connections = connections?.size ?: 0,
        )
    }
    Spacer(modifier = Modifier.padding(4.dp))
}

@Composable
private fun ConnectionsDialog(
    connections: List<Connection>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
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
                            onCancel = onDismiss,
                        )
                    }
                }
                items(connections) {
                    Text("${it.name} - ${it.remoteAddress} - ${it.since.formatLongToCustomDateTimeWithSeconds()}")
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
