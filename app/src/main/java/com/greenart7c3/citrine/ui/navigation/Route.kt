package com.greenart7c3.citrine.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector

@Immutable
sealed class Route(
    val route: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    data object Home : Route(
        route = "Home",
        icon = Icons.Outlined.Home,
        selectedIcon = Icons.Default.Home,
    )

    data object Settings : Route(
        route = "Settings",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object Logs : Route(
        route = "Logs",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object Feed : Route(
        route = "Feed/{kind}",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object DatabaseInfo : Route(
        route = "DatabaseInfo",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object ContactsScreen : Route(
        route = "Contacts/{pubkey}",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )
}
