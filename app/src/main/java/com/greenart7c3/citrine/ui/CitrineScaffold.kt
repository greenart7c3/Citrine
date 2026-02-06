package com.greenart7c3.citrine.ui

import android.annotation.SuppressLint
import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventPagingSource
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.service.crashreports.DisplayCrashMessages
import com.greenart7c3.citrine.ui.components.CitrineBottomBar
import com.greenart7c3.citrine.ui.components.CitrineTopAppBar
import com.greenart7c3.citrine.ui.components.DatabaseInfo
import com.greenart7c3.citrine.ui.components.DatabaseInfoViewModel
import com.greenart7c3.citrine.ui.components.DatabaseInfoViewModelFactory
import com.greenart7c3.citrine.ui.components.EventSection
import com.greenart7c3.citrine.ui.components.TagsSection
import com.greenart7c3.citrine.ui.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun copyToClipboard(clipboard: Clipboard, text: String) {
    Citrine.instance.applicationScope.launch(Dispatchers.Main) {
        clipboard.setClipEntry(
            ClipEntry(
                ClipData.newPlainText("Tags", text),
            ),
        )
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun CitrineScaffold(
    storageHelper: SimpleStorageHelper,
) {
    val coroutineScope = rememberCoroutineScope()
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
            CitrineTopAppBar(
                destinationRoute = destinationRoute,
            )
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
                HomeScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding)
                        .padding(horizontal = verticalPadding)
                        .padding(top = verticalPadding * 1.5f),
                    navController = navController,
                    storageHelper = storageHelper,
                    homeViewModel = homeViewModel,
                )
            }

            composable(
                Route.Feed.route,
                arguments = listOf(navArgument("kind") { type = NavType.IntType }),
                content = {
                    it.arguments?.getInt("kind")?.let { kind ->
                        val context = LocalContext.current
                        val database = AppDatabase.getDatabase(context)
                        val clipboard = LocalClipboard.current

                        val pager = Pager(
                            config = PagingConfig(
                                pageSize = 20,
                                initialLoadSize = 40,
                                prefetchDistance = 5,
                                enablePlaceholders = false,
                            ),
                            pagingSourceFactory = {
                                EventPagingSource(
                                    dao = database.eventDao(),
                                    kind = kind,
                                )
                            },
                        )

                        val events = pager.flow.collectAsLazyPagingItems()

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                        ) {
                            items(events.itemCount) { index ->
                                val event = events[index]
                                event?.let { eventWithTags ->
                                    val event = eventWithTags.toEvent()

                                    Card(
                                        modifier = Modifier
                                            .padding(16.dp),
                                    ) {
                                        EventSection(
                                            label = stringResource(R.string.kind),
                                            displayValue = "${event.kind}",
                                            onCopy = {
                                                copyToClipboard(clipboard, "${event.kind}")
                                            },
                                        )
                                        EventSection(
                                            label = stringResource(R.string.pubkey),
                                            displayValue = event.pubKey.toShortenHex(),
                                            onCopy = {
                                                copyToClipboard(clipboard, event.pubKey)
                                            },
                                        )
                                        EventSection(
                                            label = stringResource(R.string.date),
                                            displayValue = event.createdAt.formatLongToCustomDateTimeWithSeconds(),
                                            onCopy = {
                                                copyToClipboard(clipboard, "${event.createdAt}")
                                            },
                                        )
                                        if (event.content.isNotEmpty()) {
                                            EventSection(
                                                label = stringResource(R.string.content),
                                                displayValue = event.content,
                                                onCopy = {
                                                    copyToClipboard(clipboard, event.content)
                                                },
                                            )
                                        }
                                        if (event.tags.isNotEmpty()) {
                                            TagsSection(
                                                label = stringResource(R.string.tags),
                                                tags = event.tags,
                                                onCopy = {
                                                    copyToClipboard(
                                                        clipboard,
                                                        event.tags.joinToString(separator = ", ") { mainTag -> "[${mainTag.joinToString(separator = ", ") { tag -> "\"${tag}\"" }}]" },
                                                    )
                                                },
                                            )
                                        }
                                        ElevatedButton(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            onClick = {
                                                copyToClipboard(clipboard, event.toJson())
                                            },
                                            content = {
                                                Text(stringResource(R.string.copy_raw_json))
                                            },
                                        )
                                    }
                                }
                            }

                            events.apply {
                                when (loadState.refresh) {
                                    is LoadState.Loading -> item {
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier
                                                    .padding(16.dp),
                                            )
                                        }
                                    }
                                    else -> { }
                                }
                            }

                            item {
                                if (events.loadState.append is LoadState.Loading) {
                                    Box(
                                        Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .padding(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            )

            composable(
                Route.ContactsScreen.route,
                arguments = listOf(navArgument("pubkey") { type = NavType.StringType }, navArgument("packageName") { type = NavType.StringType }),
                content = {
                    it.arguments?.getString("pubkey")?.let { pubkey ->
                        ContactsScreen(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(16.dp),
                            pubKey = pubkey,
                            packageName = it.arguments?.getString("packageName") ?: "",
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
                    storageHelper = storageHelper,
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
                val viewModel = viewModel<DatabaseInfoViewModel>(
                    factory = DatabaseInfoViewModelFactory(AppDatabase.getDatabase(context)),
                )
                DatabaseInfo(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    database = AppDatabase.getDatabase(context),
                    navController = navController,
                    viewModel = viewModel,
                )
            }

            composable(Route.DownloadYourEventsUserScreen.route) {
                DownloadYourEventsUserScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    navController = navController,
                )
            }
        }
        DisplayCrashMessages()
    }
}
