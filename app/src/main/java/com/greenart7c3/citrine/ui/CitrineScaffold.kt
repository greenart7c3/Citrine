package com.greenart7c3.citrine.ui

import android.annotation.SuppressLint
import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.ui.components.CitrineBottomBar
import com.greenart7c3.citrine.ui.components.CitrineTopAppBar
import com.greenart7c3.citrine.ui.components.DatabaseInfo
import com.greenart7c3.citrine.ui.components.DatabaseInfoViewModel
import com.greenart7c3.citrine.ui.components.EventSection
import com.greenart7c3.citrine.ui.components.TagsSection
import com.greenart7c3.citrine.ui.navigation.Route
import com.greenart7c3.citrine.ui.settings.AccessControlSettingsScreen
import com.greenart7c3.citrine.ui.settings.AggregatorSettingsScreen
import com.greenart7c3.citrine.ui.settings.BackupSettingsScreen
import com.greenart7c3.citrine.ui.settings.BrowseNsitesScreen
import com.greenart7c3.citrine.ui.settings.NetworkSettingsScreen
import com.greenart7c3.citrine.ui.settings.Nip86SettingsScreen
import com.greenart7c3.citrine.ui.settings.NsiteRelaysSettingsScreen
import com.greenart7c3.citrine.ui.settings.RelayInfoSettingsScreen
import com.greenart7c3.citrine.ui.settings.RetentionSettingsScreen
import com.greenart7c3.citrine.ui.settings.WebClientsSettingsScreen
import com.greenart7c3.citrine.ui.settings.toShortenHex
import com.greenart7c3.citrine.utils.formatEpochSecondsToCustomDateTimeWithSeconds
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun copyToClipboard(scope: CoroutineScope, clipboard: Clipboard, text: String) {
    scope.launch(Dispatchers.Main) {
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
    storageHelper: StoragePickerHelper,
    modifier: Modifier = Modifier,
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(LocalContext.current.applicationContext, LocalAppScope.current)),
) {
    val coroutineScope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val homeViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val destinationRoute = navBackStackEntry?.destination?.route ?: ""
    val restartRelay: () -> Unit = {
        coroutineScope.launch(Dispatchers.IO) {
            homeViewModel.stop(appContext)
            delay(1000)
            homeViewModel.start(appContext)
        }
    }

    Scaffold(
        modifier = modifier,
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

        CitrineNavHost(
            navController = navController,
            padding = padding,
            verticalPadding = verticalPadding,
            storageHelper = storageHelper,
            homeViewModelStoreOwner = homeViewModelStoreOwner,
            restartRelay = restartRelay,
        )
    }
}

@Composable
private fun CitrineNavHost(
    navController: NavHostController,
    padding: PaddingValues,
    verticalPadding: Dp,
    storageHelper: StoragePickerHelper,
    homeViewModelStoreOwner: ViewModelStoreOwner,
    restartRelay: () -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Home.route,
    ) {
        mainDestinations(
            navController = navController,
            padding = padding,
            verticalPadding = verticalPadding,
            storageHelper = storageHelper,
            homeViewModelStoreOwner = homeViewModelStoreOwner,
        )

        val subScreenModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)

        settingsDestinations(
            navController = navController,
            subScreenModifier = subScreenModifier,
            storageHelper = storageHelper,
            restartRelay = restartRelay,
        )

        toolDestinations(
            navController = navController,
            padding = padding,
        )
    }
}

private fun NavGraphBuilder.mainDestinations(
    navController: NavHostController,
    padding: PaddingValues,
    verticalPadding: Dp,
    storageHelper: StoragePickerHelper,
    homeViewModelStoreOwner: ViewModelStoreOwner,
) {
    composable(Route.Logs.route) {
        LogcatScreen(
            onClose = {
                navController.navigateUp()
            },
        )
    }

    composable(Route.Home.route) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides homeViewModelStoreOwner) {
            HomeScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = verticalPadding)
                    .padding(top = verticalPadding * 1.5f),
                navController = navController,
                storageHelper = storageHelper,
            )
        }
    }

    composable(
        Route.Feed.route,
        arguments = listOf(navArgument("kind") { type = NavType.IntType }),
        content = {
            it.arguments?.getInt("kind")?.let { kind ->
                FeedScreen(
                    kind = kind,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
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
}

private fun NavGraphBuilder.settingsDestinations(
    navController: NavHostController,
    subScreenModifier: Modifier,
    storageHelper: StoragePickerHelper,
    restartRelay: () -> Unit,
) {
    composable(Route.Settings.route) {
        SettingsScreen(
            modifier = subScreenModifier,
            navController = navController,
            onApplyChanges = restartRelay,
        )
    }

    composable(Route.RelayInfoSettings.route) {
        RelayInfoSettingsScreen(modifier = subScreenModifier, onApplyChanges = restartRelay)
    }
    composable(Route.AccessControlSettings.route) {
        AccessControlSettingsScreen(modifier = subScreenModifier, onApplyChanges = restartRelay)
    }
    composable(Route.NetworkSettings.route) {
        NetworkSettingsScreen(modifier = subScreenModifier, onApplyChanges = restartRelay)
    }
    composable(Route.AggregatorSettings.route) {
        AggregatorSettingsScreen(modifier = subScreenModifier, onApplyChanges = restartRelay)
    }
    composable(Route.RetentionSettings.route) {
        RetentionSettingsScreen(modifier = subScreenModifier, onApplyChanges = restartRelay)
    }
    composable(Route.BackupSettings.route) {
        BackupSettingsScreen(modifier = subScreenModifier, storageHelper = storageHelper, onApplyChanges = restartRelay)
    }
    composable(Route.WebClientsSettings.route) {
        WebClientsSettingsScreen(
            modifier = subScreenModifier,
            storageHelper = storageHelper,
            onApplyChanges = restartRelay,
            onBrowseNsites = { navController.navigate(Route.BrowseNsites.route) },
            onNsiteRelays = { navController.navigate(Route.NsiteRelaysSettings.route) },
        )
    }
    composable(Route.Nip86Settings.route) {
        Nip86SettingsScreen(modifier = subScreenModifier, onApplyChanges = restartRelay)
    }
    composable(Route.BrowseNsites.route) {
        BrowseNsitesScreen(modifier = subScreenModifier)
    }
    composable(Route.NsiteRelaysSettings.route) {
        NsiteRelaysSettingsScreen(modifier = subScreenModifier)
    }
}

private fun NavGraphBuilder.toolDestinations(
    navController: NavHostController,
    padding: PaddingValues,
) {
    composable(Route.DatabaseInfo.route) {
        val viewModel = viewModel<DatabaseInfoViewModel>(
            factory = DatabaseInfoViewModel.factory(LocalContext.current.applicationContext, LocalAppScope.current),
        )
        DatabaseInfo(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
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

    composable(Route.Rebroadcast.route) {
        RebroadcastScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        )
    }
}

@Composable
private fun FeedScreen(
    kind: Int,
    modifier: Modifier = Modifier,
    feedViewModel: FeedViewModel = viewModel(
        key = "feed-$kind",
        factory = FeedViewModel.factory(LocalContext.current.applicationContext, kind),
    ),
) {
    val appScope = LocalAppScope.current
    val clipboard = LocalClipboard.current

    val events = feedViewModel.events.collectAsLazyPagingItems()

    LazyColumn(
        modifier = modifier,
    ) {
        items(events.itemCount) { index ->
            events[index]?.let { event ->
                FeedEventCard(
                    event = event,
                    onCopy = { text -> copyToClipboard(appScope, clipboard, text) },
                )
            }
        }

        events.apply {
            when (loadState.refresh) {
                is LoadState.Loading -> item {
                    FeedLoadingIndicator()
                }
                else -> { }
            }
        }

        item {
            if (events.loadState.append is LoadState.Loading) {
                FeedLoadingIndicator()
            }
        }
    }
}

@Composable
private fun FeedEventCard(
    event: Event,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(16.dp),
    ) {
        FeedEventFields(event = event, onCopy = onCopy)
        ElevatedButton(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            onClick = {
                onCopy(event.toJson())
            },
            content = {
                Text(stringResource(R.string.copy_raw_json))
            },
        )
    }
}

@Composable
private fun ColumnScope.FeedEventFields(
    event: Event,
    onCopy: (String) -> Unit,
) {
    EventSection(
        label = stringResource(R.string.kind),
        displayValue = "${event.kind}",
        onCopy = {
            onCopy("${event.kind}")
        },
    )
    EventSection(
        label = stringResource(R.string.pubkey),
        displayValue = event.pubKey.toShortenHex(),
        onCopy = {
            onCopy(event.pubKey)
        },
    )
    EventSection(
        label = stringResource(R.string.date),
        displayValue = event.createdAt.formatEpochSecondsToCustomDateTimeWithSeconds(),
        onCopy = {
            onCopy("${event.createdAt}")
        },
    )
    if (event.content.isNotEmpty()) {
        EventSection(
            label = stringResource(R.string.content),
            displayValue = event.content,
            onCopy = {
                onCopy(event.content)
            },
        )
    }
    if (event.tags.isNotEmpty()) {
        TagsSection(
            label = stringResource(R.string.tags),
            tags = event.tags,
            onCopy = {
                onCopy(
                    event.tags.joinToString(separator = ", ") { mainTag -> "[${mainTag.joinToString(separator = ", ") { tag -> "\"${tag}\"" }}]" },
                )
            },
        )
    }
}

@Composable
private fun FeedLoadingIndicator() {
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
