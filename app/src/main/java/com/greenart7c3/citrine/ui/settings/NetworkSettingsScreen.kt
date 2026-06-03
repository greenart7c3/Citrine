package com.greenart7c3.citrine.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.TorManager
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NetworkSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Surface(modifier) {
        var startOnBoot by remember { mutableStateOf(Settings.startOnBoot) }
        var listenToPokeyBroadcasts by remember { mutableStateOf(Settings.listenToPokeyBroadcasts) }
        var sendMuteResponse by remember { mutableStateOf(Settings.sendMuteResponse) }
        var useProxy by remember { mutableStateOf(Settings.useProxy) }
        var proxyAllUrls by remember { mutableStateOf(Settings.proxyAllUrls) }
        var useTor by remember { mutableStateOf(Settings.useTor) }
        val torState = TorManager.state.collectAsStateWithLifecycle()

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.startOnBoot = startOnBoot
                Settings.listenToPokeyBroadcasts = listenToPokeyBroadcasts
                Settings.sendMuteResponse = sendMuteResponse
                Settings.useProxy = useProxy
                Settings.proxyAllUrls = proxyAllUrls
                Settings.useTor = useTor
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                if (listenToPokeyBroadcasts) {
                    Citrine.instance.registerPokeyReceiver()
                } else {
                    Citrine.instance.unregisterPokeyReceiver()
                }
                onApplyChanges()
                delay(1500)
                isLoading = false
            }
            Unit
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.start_on_boot),
                        description = stringResource(R.string.start_on_boot_description),
                        checked = startOnBoot,
                        onCheckedChange = { startOnBoot = it },
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.listen_to_pokey_broadcasts),
                        description = stringResource(R.string.listen_to_pokey_broadcasts_description),
                        checked = listenToPokeyBroadcasts,
                        onCheckedChange = { listenToPokeyBroadcasts = it },
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.send_mute_response),
                        description = stringResource(R.string.send_mute_response_description),
                        checked = sendMuteResponse,
                        onCheckedChange = { sendMuteResponse = it },
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.use_proxy),
                        description = stringResource(R.string.use_proxy_description),
                        checked = useProxy,
                        onCheckedChange = { useProxy = it },
                    )
                }
                if (useProxy) {
                    item {
                        SwitchSettingRow(
                            title = stringResource(R.string.proxy_all_urls),
                            description = stringResource(R.string.proxy_all_urls_description),
                            checked = proxyAllUrls,
                            onCheckedChange = { proxyAllUrls = it },
                        )
                    }
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.use_tor),
                        description = stringResource(R.string.use_tor_description),
                        checked = useTor,
                        onCheckedChange = { useTor = it },
                    )
                }
                if (useTor) {
                    val displayHostname = when (val s = torState.value) {
                        is TorManager.State.Running -> s.hostname
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

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
