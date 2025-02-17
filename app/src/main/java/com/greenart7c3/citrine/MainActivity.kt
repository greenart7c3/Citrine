package com.greenart7c3.citrine

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toTags
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.ContactsScreen
import com.greenart7c3.citrine.ui.HomeViewModel
import com.greenart7c3.citrine.ui.LogcatScreen
import com.greenart7c3.citrine.ui.SettingsScreen
import com.greenart7c3.citrine.ui.components.CitrineBottomBar
import com.greenart7c3.citrine.ui.components.DatabaseInfo
import com.greenart7c3.citrine.ui.components.RelayInfo
import com.greenart7c3.citrine.ui.dialogs.ImportEventsDialog
import com.greenart7c3.citrine.ui.navigation.Route
import com.greenart7c3.citrine.ui.theme.CitrineTheme
import com.greenart7c3.citrine.ui.toShortenHex
import com.greenart7c3.citrine.utils.toDateString
import com.vitorpamplona.quartz.encoders.Nip19Bech32
import com.vitorpamplona.quartz.signers.ExternalSignerLauncher
import com.vitorpamplona.quartz.signers.NostrSignerExternal
import com.vitorpamplona.quartz.signers.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val storageHelper = SimpleStorageHelper(this@MainActivity)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val coroutineScope = rememberCoroutineScope()

            CitrineTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val destinationRoute = navBackStackEntry?.destination?.route ?: ""

                Scaffold(
                    bottomBar = {
                        CitrineBottomBar(
                            destinationRoute = destinationRoute,
                            navController = navController,
                        )
                    },
                    topBar = {
                        if (destinationRoute.startsWith("Feed") || destinationRoute == Route.DatabaseInfo.route || destinationRoute.startsWith("Contacts")) {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        text = if (destinationRoute.startsWith("Feed")) {
                                            stringResource(R.string.feed)
                                        } else if (destinationRoute == Route.DatabaseInfo.route) {
                                            stringResource(R.string.database)
                                        } else {
                                            stringResource(R.string.restore_follows)
                                        },
                                    )
                                },
                            )
                        }
                    },
                ) { padding ->
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp
                    val percentage = (screenWidthDp * 0.93f)
                    val verticalPadding = (screenWidthDp - percentage)
                    val homeViewModel: HomeViewModel = viewModel()

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
                            val context = LocalContext.current

                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(padding)
                                    .padding(horizontal = verticalPadding)
                                    .padding(top = verticalPadding * 1.5f),
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
                                                getString(R.string.sign_request_rejected),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } else {
                                            result.data?.let {
                                                try {
                                                    val key = it.getStringExtra("signature") ?: ""
                                                    val packageName = it.getStringExtra("package") ?: ""

                                                    val returnedKey = if (key.startsWith("npub")) {
                                                        when (val parsed = Nip19Bech32.uriToRoute(key)?.entity) {
                                                            is Nip19Bech32.NPub -> parsed.hex
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
                                                try {
                                                    val key = it.getStringExtra("signature") ?: ""
                                                    val packageName = it.getStringExtra("package") ?: ""

                                                    val returnedKey = if (key.startsWith("npub")) {
                                                        when (val parsed = Nip19Bech32.uriToRoute(key)?.entity) {
                                                            is Nip19Bech32.NPub -> parsed.hex
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
                                                    homeViewModel.loadEventsFromPubKey(database)
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
                                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, this@MainActivity)
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
                                    AlertDialog(
                                        onDismissRequest = {
                                            deleteAllDialog = false
                                        },
                                        title = {
                                            Text(stringResource(R.string.delete_all_events))
                                        },
                                        text = {
                                            Text(stringResource(R.string.delete_all_events_warning))
                                        },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    deleteAllDialog = false
                                                    Citrine.getInstance().cancelJob()
                                                    Citrine.getInstance().applicationScope.launch(Dispatchers.IO) {
                                                        Citrine.getInstance().job?.join()
                                                        Citrine.getInstance().isImportingEvents = true
                                                        homeViewModel.setProgress("Deleting all events")
                                                        database.clearAllTables()
                                                        homeViewModel.setProgress("")
                                                        Citrine.getInstance().isImportingEvents = false
                                                    }
                                                },
                                            ) {
                                                Text(stringResource(R.string.yes))
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(
                                                onClick = {
                                                    deleteAllDialog = false
                                                },
                                            ) {
                                                Text(stringResource(R.string.no))
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
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
                                                    val signerType = "get_public_key"
                                                    intent.putExtra("type", signerType)
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                                    launcherLoginContacts.launch(intent)
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
                                                deleteAllDialog = true
                                            },
                                            content = {
                                                Text(stringResource(R.string.delete_all_events))
                                            },
                                        )

                                        ElevatedButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = {
                                                try {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
                                                    val signerType = "get_public_key"
                                                    intent.putExtra("type", signerType)

                                                    val permissions =
                                                        listOf(
                                                            Permission(
                                                                "sign_event",
                                                                22242,
                                                            ),
                                                        )
                                                    val jsonArray = StringBuilder("[")
                                                    permissions.forEachIndexed { index, permission ->
                                                        jsonArray.append(permission.toJson())
                                                        if (index < permissions.size - 1) {
                                                            jsonArray.append(",")
                                                        }
                                                    }
                                                    jsonArray.append("]")

                                                    intent.putExtra("permissions", jsonArray.toString())
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
                                            content = {
                                                Text("Download your events")
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

                                        Spacer(modifier = Modifier.padding(4.dp))

                                        val connectionFlow = CustomWebSocketService.server?.connections?.collectAsStateWithLifecycle(initialValue = listOf())

                                        RelayInfo(
                                            modifier = Modifier
                                                .fillMaxWidth(),
                                            connections = connectionFlow?.value?.size ?: 0,
                                        )
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

                        composable(
                            Route.Feed.route,
                            arguments = listOf(navArgument("kind") { type = NavType.IntType }),
                            content = {
                                it.arguments?.getInt("kind")?.let { kind ->
                                    val context = LocalContext.current
                                    val database = AppDatabase.getDatabase(context)
                                    val events = database.eventDao().getByKind(kind).collectAsStateWithLifecycle(emptyList())

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(padding),
                                    ) {
                                        items(events.value) { event ->
                                            var showTags by remember { mutableStateOf(false) }

                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                            ) {
                                                Text(event.event.kind.toString())
                                                Text(event.event.createdAt.toDateString())
                                                Text(event.event.pubkey.toShortenHex())
                                                Text(event.event.content)
                                                if (event.tags.isNotEmpty()) {
                                                    Row(
                                                        Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.Center,
                                                    ) {
                                                        ElevatedButton(
                                                            onClick = {
                                                                showTags = !showTags
                                                            },
                                                            content = {
                                                                Text("Show/Hide tags")
                                                            },
                                                        )
                                                    }
                                                    if (showTags) {
                                                        event.tags.forEach { tag ->
                                                            Text(tag.toTags().toList().toString())
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        )

                        composable(
                            Route.ContactsScreen.route,
                            arguments = listOf(navArgument("pubkey") { type = NavType.StringType }),
                            content = {
                                it.arguments?.getString("pubkey")?.let { pubkey ->
                                    ContactsScreen(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(padding)
                                            .padding(16.dp),
                                        pubKey = pubkey,
                                        navController = navController,
                                    )
                                }
                            },
                        )

                        composable(Route.Settings.route) {
                            val context = LocalContext.current
                            SettingsScreen(
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(16.dp),
                                onApplyChanges = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        homeViewModel.stop(context)
                                        delay(1000)
                                        homeViewModel.start(context)
                                    }
                                },
                            )
                        }

                        composable(Route.DatabaseInfo.route) {
                            val context = LocalContext.current
                            DatabaseInfo(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(16.dp),
                                database = AppDatabase.getDatabase(context),
                                navController = navController,
                            )
                        }
                    }
                }
            }
        }
    }
}
