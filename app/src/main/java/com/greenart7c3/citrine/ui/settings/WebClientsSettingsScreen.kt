package com.greenart7c3.citrine.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.NsiteInfo
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.ui.StoragePickerHelper
import com.greenart7c3.citrine.ui.components.NsiteRow
import com.greenart7c3.citrine.ui.components.WebAppInput
import com.greenart7c3.citrine.ui.components.WebAppRow

@Composable
fun WebClientsSettingsScreen(
    storageHelper: StoragePickerHelper,
    onApplyChanges: () -> Unit,
    onBrowseNsites: () -> Unit,
    onNsiteRelays: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebClientsSettingsViewModel = viewModel(factory = WebClientsSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.reloadNsites()
    }

    Surface(modifier) {
        storageHelper.onFolderSelected = { _, folder ->
            viewModel.addWebClient(folder.uri.toString())
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                webClientItems(
                    webPath = viewModel.webPath,
                    onWebPathChange = { viewModel.webPath = it },
                    onAdd = { nameNotBlankResource ->
                        if (viewModel.webPath.text.isBlank()) {
                            Toast.makeText(context, nameNotBlankResource, Toast.LENGTH_SHORT).show()
                        } else {
                            storageHelper.openFolderPicker()
                        }
                    },
                    paths = viewModel.webClients.keys.toList(),
                    onOpen = { item ->
                        val browserIntent = Intent(
                            Intent.ACTION_VIEW,
                            "http://${item.removePrefix("/")}.localhost:${Settings.port}".toUri(),
                        )
                        browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(browserIntent)
                    },
                    onDelete = viewModel::removeWebClient,
                )
                nsiteHeaderItem(
                    onBrowseNsites = onBrowseNsites,
                    onCheckForUpdates = viewModel::checkForUpdates,
                    onNsiteRelays = onNsiteRelays,
                )
                nsiteItems(
                    nsites = viewModel.nsites,
                    updatesAvailable = viewModel.updatesAvailable,
                    onOpen = { nsite ->
                        val browserIntent = Intent(
                            Intent.ACTION_VIEW,
                            "http://${nsite.folderName}.localhost:${Settings.port}".toUri(),
                        )
                        browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(browserIntent)
                    },
                    onToggleAutoUpdate = viewModel::setAutoUpdate,
                    onUpdate = viewModel::applyUpdate,
                    onDelete = viewModel::deleteNsite,
                )
            }

            SettingsApplyBar(enabled = !viewModel.isLoading, onApply = { viewModel.applyChanges(onApplyChanges) })
        }
    }
}

private fun LazyListScope.webClientItems(
    webPath: TextFieldValue,
    onWebPathChange: (TextFieldValue) -> Unit,
    onAdd: (nameNotBlankMessage: String) -> Unit,
    paths: List<String>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    item {
        val nameNotBlankResource = stringResource(R.string.name_cannot_be_blank)
        WebAppInput(
            value = webPath,
            onValueChange = onWebPathChange,
            onAdd = { onAdd(nameNotBlankResource) },
        )
    }
    items(paths) { item ->
        WebAppRow(
            onOpenWebApp = { onOpen(item) },
            onDelete = { onDelete(item) },
            item = item,
        )
    }
}

private fun LazyListScope.nsiteHeaderItem(
    onBrowseNsites: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onNsiteRelays: () -> Unit,
) {
    item {
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(
            stringResource(R.string.nsites),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Button(onClick = onBrowseNsites) {
            Text(stringResource(R.string.browse_nsites))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onCheckForUpdates) {
                Text(stringResource(R.string.check_for_updates))
            }
            TextButton(onClick = onNsiteRelays) {
                Text(stringResource(R.string.nsite_relays))
            }
        }
    }
}

private fun LazyListScope.nsiteItems(
    nsites: List<NsiteInfo>,
    updatesAvailable: Set<String>,
    onOpen: (NsiteInfo) -> Unit,
    onToggleAutoUpdate: (address: String, enabled: Boolean) -> Unit,
    onUpdate: (address: String) -> Unit,
    onDelete: (address: String) -> Unit,
) {
    items(nsites) { nsite ->
        NsiteRow(
            nsite = nsite,
            updateAvailable = updatesAvailable.contains(nsite.address),
            onOpen = { onOpen(nsite) },
            onToggleAutoUpdate = { enabled -> onToggleAutoUpdate(nsite.address, enabled) },
            onUpdate = { onUpdate(nsite.address) },
            onDelete = { onDelete(nsite.address) },
        )
    }
}
