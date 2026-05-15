package com.greenart7c3.citrine.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.ui.navigation.Route
import com.greenart7c3.citrine.ui.navigation.navigationItems
import com.greenart7c3.citrine.ui.navigation.settingsSubRoutes

@Composable
fun CitrineBottomBar(
    destinationRoute: String,
    navController: NavController,
) {
    val isBackBarRoute = destinationRoute.startsWith("Feed") ||
        destinationRoute == Route.DatabaseInfo.route ||
        destinationRoute.startsWith("Contacts") ||
        destinationRoute == Route.DownloadYourEventsUserScreen.route ||
        destinationRoute in settingsSubRoutes
    val isHiddenRoute = destinationRoute == Route.Logs.route

    when {
        isBackBarRoute -> {
            BottomAppBar {
                IconRow(
                    center = true,
                    title = stringResource(R.string.go_back),
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { navController.navigateUp() },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        isHiddenRoute -> { /* no bottom bar */ }
        else -> {
            NavigationBar(tonalElevation = 0.dp) {
                navigationItems.forEach {
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
                        label = { Text(it.route) },
                    )
                }
            }
        }
    }
}
