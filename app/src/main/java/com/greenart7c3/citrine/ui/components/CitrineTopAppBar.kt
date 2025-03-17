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
    if (destinationRoute.startsWith("Feed") || destinationRoute == Route.DatabaseInfo.route || destinationRoute.startsWith("Contacts") || destinationRoute == Route.DownloadYourEventsUserScreen.route) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = if (destinationRoute.startsWith("Feed")) {
                        stringResource(R.string.feed)
                    } else if (destinationRoute == Route.DatabaseInfo.route) {
                        stringResource(R.string.database)
                    } else if (destinationRoute == Route.DownloadYourEventsUserScreen.route) {
                        stringResource(R.string.download_your_events)
                    } else {
                        stringResource(R.string.restore_follows)
                    },
                )
            },
        )
    }
}
