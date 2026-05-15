package com.greenart7c3.citrine.ui.components

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.ui.navigation.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitrineTopAppBar(
    destinationRoute: String,
) {
    val titleRes = titleResForRoute(destinationRoute) ?: return
    CenterAlignedTopAppBar(
        title = { Text(text = stringResource(titleRes)) },
    )
}

private fun titleResForRoute(destinationRoute: String): Int? = when {
    destinationRoute.startsWith("Feed") -> R.string.feed
    destinationRoute == Route.DatabaseInfo.route -> R.string.database
    destinationRoute.startsWith("Contacts") -> R.string.restore_follows
    destinationRoute == Route.DownloadYourEventsUserScreen.route -> R.string.download_your_events
    destinationRoute == Route.RelayInfoSettings.route -> R.string.settings_category_relay_info
    destinationRoute == Route.AccessControlSettings.route -> R.string.settings_category_access_control
    destinationRoute == Route.NetworkSettings.route -> R.string.settings_category_network
    destinationRoute == Route.AggregatorSettings.route -> R.string.settings_category_aggregator
    destinationRoute == Route.RetentionSettings.route -> R.string.settings_category_retention
    destinationRoute == Route.BackupSettings.route -> R.string.settings_category_backup
    destinationRoute == Route.WebClientsSettings.route -> R.string.settings_category_web_clients
    else -> null
}
