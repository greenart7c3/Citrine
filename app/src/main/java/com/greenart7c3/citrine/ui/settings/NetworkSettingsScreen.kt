package com.greenart7c3.citrine.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.TorManager
import com.greenart7c3.citrine.ui.LocalPokeyListener
import com.greenart7c3.citrine.ui.components.SwitchSettingRow

@Composable
fun NetworkSettingsScreen(
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NetworkSettingsViewModel = viewModel(factory = NetworkSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val pokeyListener = LocalPokeyListener.current

    Surface(modifier) {
        val torState = TorManager.state.collectAsStateWithLifecycle()

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                relaySwitchItems(
                    startOnBoot = viewModel.startOnBoot,
                    onStartOnBootChange = { viewModel.startOnBoot = it },
                    listenToPokeyBroadcasts = viewModel.listenToPokeyBroadcasts,
                    onListenToPokeyBroadcastsChange = { viewModel.listenToPokeyBroadcasts = it },
                    sendMuteResponse = viewModel.sendMuteResponse,
                    onSendMuteResponseChange = { viewModel.sendMuteResponse = it },
                )
                proxyItems(
                    useProxy = viewModel.useProxy,
                    onUseProxyChange = { viewModel.useProxy = it },
                    proxyAllUrls = viewModel.proxyAllUrls,
                    onProxyAllUrlsChange = { viewModel.proxyAllUrls = it },
                )
                torItems(
                    useTor = viewModel.useTor,
                    onUseTorChange = { viewModel.useTor = it },
                    torState = torState.value,
                )
            }

            SettingsApplyBar(
                enabled = !viewModel.isLoading,
                onApply = { viewModel.applyChanges(pokeyListener, onApplyChanges) },
            )
        }
    }
}

private fun LazyListScope.relaySwitchItems(
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    listenToPokeyBroadcasts: Boolean,
    onListenToPokeyBroadcastsChange: (Boolean) -> Unit,
    sendMuteResponse: Boolean,
    onSendMuteResponseChange: (Boolean) -> Unit,
) {
    item {
        SwitchSettingRow(
            title = stringResource(R.string.start_on_boot),
            description = stringResource(R.string.start_on_boot_description),
            checked = startOnBoot,
            onCheckedChange = onStartOnBootChange,
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.listen_to_pokey_broadcasts),
            description = stringResource(R.string.listen_to_pokey_broadcasts_description),
            checked = listenToPokeyBroadcasts,
            onCheckedChange = onListenToPokeyBroadcastsChange,
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.send_mute_response),
            description = stringResource(R.string.send_mute_response_description),
            checked = sendMuteResponse,
            onCheckedChange = onSendMuteResponseChange,
        )
    }
}

private fun LazyListScope.proxyItems(
    useProxy: Boolean,
    onUseProxyChange: (Boolean) -> Unit,
    proxyAllUrls: Boolean,
    onProxyAllUrlsChange: (Boolean) -> Unit,
) {
    item {
        SwitchSettingRow(
            title = stringResource(R.string.use_proxy),
            description = stringResource(R.string.use_proxy_description),
            checked = useProxy,
            onCheckedChange = onUseProxyChange,
        )
    }
    if (useProxy) {
        item {
            SwitchSettingRow(
                title = stringResource(R.string.proxy_all_urls),
                description = stringResource(R.string.proxy_all_urls_description),
                checked = proxyAllUrls,
                onCheckedChange = onProxyAllUrlsChange,
            )
        }
    }
}

private fun LazyListScope.torItems(
    useTor: Boolean,
    onUseTorChange: (Boolean) -> Unit,
    torState: TorManager.State,
) {
    item {
        SwitchSettingRow(
            title = stringResource(R.string.use_tor),
            description = stringResource(R.string.use_tor_description),
            checked = useTor,
            onCheckedChange = onUseTorChange,
        )
    }
    if (useTor) {
        val displayHostname = when (torState) {
            is TorManager.State.Running -> torState.hostname
            else -> Settings.onionHostname
        }
        if (displayHostname.isNotBlank()) {
            item {
                OutlinedTextField(
                    value = TextFieldValue("ws://$displayHostname"),
                    onValueChange = { },
                    readOnly = true,
                    label = { Text(stringResource(R.string.onion_address)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    singleLine = true,
                )
            }
        }
    }
}
