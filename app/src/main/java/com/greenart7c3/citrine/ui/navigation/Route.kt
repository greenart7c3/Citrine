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
        route = "Contacts/{pubkey}/{packageName}",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object DownloadYourEventsUserScreen : Route(
        route = "DownloadYourEventsUserScreen",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object RelayInfoSettings : Route(
        route = "Settings/RelayInfo",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object AccessControlSettings : Route(
        route = "Settings/AccessControl",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object NetworkSettings : Route(
        route = "Settings/Network",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object AggregatorSettings : Route(
        route = "Settings/Aggregator",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object RetentionSettings : Route(
        route = "Settings/Retention",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object BackupSettings : Route(
        route = "Settings/Backup",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object WebClientsSettings : Route(
        route = "Settings/WebClients",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object Nip86Settings : Route(
        route = "Settings/Nip86",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object BrowseNsites : Route(
        route = "Settings/BrowseNsites",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )

    data object NsiteRelaysSettings : Route(
        route = "Settings/NsiteRelays",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Default.Settings,
    )
}

val navigationItems = listOf(Route.Home, Route.Settings)

val settingsSubRoutes = setOf(
    Route.RelayInfoSettings.route,
    Route.AccessControlSettings.route,
    Route.NetworkSettings.route,
    Route.AggregatorSettings.route,
    Route.RetentionSettings.route,
    Route.BackupSettings.route,
    Route.WebClientsSettings.route,
    Route.Nip86Settings.route,
    Route.BrowseNsites.route,
    Route.NsiteRelaysSettings.route,
)
