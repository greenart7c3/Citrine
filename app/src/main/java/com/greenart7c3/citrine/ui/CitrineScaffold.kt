package com.greenart7c3.citrine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.greenart7c3.citrine.ui.components.CitrineBottomBar
import com.greenart7c3.citrine.ui.components.CitrineTopAppBar
import com.greenart7c3.citrine.ui.components.DatabaseInfo
import com.greenart7c3.citrine.ui.navigation.Route
import com.greenart7c3.citrine.utils.toDateString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                DatabaseInfo(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    database = AppDatabase.getDatabase(context),
                    navController = navController,
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
    }
}
