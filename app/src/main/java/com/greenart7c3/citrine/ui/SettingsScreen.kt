package com.greenart7c3.citrine.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.ui.navigation.Route

private data class SettingsCategory(
    val route: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
)

private val settingsCategories = listOf(
    SettingsCategory(
        route = Route.RelayInfoSettings.route,
        titleRes = R.string.settings_category_relay_info,
        descriptionRes = R.string.settings_category_relay_info_description,
        icon = Icons.Filled.Storefront,
    ),
    SettingsCategory(
        route = Route.AccessControlSettings.route,
        titleRes = R.string.settings_category_access_control,
        descriptionRes = R.string.settings_category_access_control_description,
        icon = Icons.Filled.Shield,
    ),
    SettingsCategory(
        route = Route.NetworkSettings.route,
        titleRes = R.string.settings_category_network,
        descriptionRes = R.string.settings_category_network_description,
        icon = Icons.Filled.Wifi,
    ),
    SettingsCategory(
        route = Route.AggregatorSettings.route,
        titleRes = R.string.settings_category_aggregator,
        descriptionRes = R.string.settings_category_aggregator_description,
        icon = Icons.Filled.CloudSync,
    ),
    SettingsCategory(
        route = Route.RetentionSettings.route,
        titleRes = R.string.settings_category_retention,
        descriptionRes = R.string.settings_category_retention_description,
        icon = Icons.Filled.AutoDelete,
    ),
    SettingsCategory(
        route = Route.BackupSettings.route,
        titleRes = R.string.settings_category_backup,
        descriptionRes = R.string.settings_category_backup_description,
        icon = Icons.Filled.Backup,
    ),
    SettingsCategory(
        route = Route.WebClientsSettings.route,
        titleRes = R.string.settings_category_web_clients,
        descriptionRes = R.string.settings_category_web_clients_description,
        icon = Icons.Filled.Web,
    ),
    SettingsCategory(
        route = Route.Nip86Settings.route,
        titleRes = R.string.settings_category_nip86,
        descriptionRes = R.string.settings_category_nip86_description,
        icon = Icons.Filled.AdminPanelSettings,
    ),
)

@Composable
fun SettingsScreen(
    navController: NavController,
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val context = LocalContext.current
    val pokeyListener = LocalPokeyListener.current
    val settingsResetCompleteMsg = stringResource(R.string.settings_reset_complete)
    var showResetDialog by remember { mutableStateOf(false) }

    Surface(modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsCategoryList(
                onNavigate = { route -> navController.navigate(route) },
                modifier = Modifier.weight(1f),
            )
            ResetSettingsButton(onClick = { showResetDialog = true })
        }
    }

    if (showResetDialog) {
        ResetSettingsDialog(
            onConfirm = {
                showResetDialog = false
                viewModel.resetAllSettings(
                    pokeyListener = pokeyListener,
                    onApplyChanges = onApplyChanges,
                    onReset = {
                        Toast.makeText(
                            context,
                            settingsResetCompleteMsg,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

@Composable
private fun SettingsCategoryList(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(settingsCategories.size) { index ->
            SettingsCategoryCard(
                category = settingsCategories[index],
                onClick = { onNavigate(settingsCategories[index].route) },
            )
        }
        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResetSettingsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        ElevatedButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.reset_all_settings))
        }
    }
}

@Composable
private fun ResetSettingsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_all_settings)) },
        text = { Text(stringResource(R.string.reset_all_settings_warning)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SettingsCategoryCard(
    category: SettingsCategory,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.size(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(category.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(category.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
