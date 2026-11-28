package com.greenart7c3.citrine.ui.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission

@Composable
fun AggregatorSettingsScreen(
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AggregatorSettingsViewModel = viewModel(factory = AggregatorSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val context = LocalContext.current
    val paste = rememberClipboardPaste()
    val pubkeyOrRelaysRequiredMsg = stringResource(R.string.relay_aggregator_pubkey_or_relays_required)
    val invalidKindMsg = stringResource(R.string.invalid_kind)
    val invalidRelayMsg = stringResource(R.string.relay_aggregator_invalid_relay)

    Surface(modifier) {
        val loginWithAggregatorSigner = rememberAggregatorSignerLogin(onLogin = viewModel::loginWithExternalSigner)

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                aggregatorAccountItems(
                    enabled = viewModel.relayAggregatorEnabled,
                    onEnabledChange = {
                        if (!viewModel.changeEnabled(it)) {
                            Toast.makeText(context, pubkeyOrRelaysRequiredMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    pubkey = viewModel.aggregatorPubkey,
                    onPubkeyChange = { viewModel.aggregatorPubkey = it },
                    onSignerLogin = loginWithAggregatorSigner,
                    refreshMinutes = viewModel.aggregatorRefreshMinutes,
                    onRefreshMinutesChange = { viewModel.aggregatorRefreshMinutes = it },
                )
                aggregatorNetworkItems(
                    includeTagged = viewModel.aggregatorIncludeTagged,
                    onIncludeTaggedChange = { viewModel.aggregatorIncludeTagged = it },
                    wifiOnly = viewModel.aggregatorWifiOnly,
                    onWifiOnlyChange = { viewModel.aggregatorWifiOnly = it },
                    pauseOnLimitedNetwork = viewModel.aggregatorPauseOnLimitedNetwork,
                    onPauseOnLimitedNetworkChange = { viewModel.aggregatorPauseOnLimitedNetwork = it },
                )
                aggregatorKindItems(
                    kinds = viewModel.aggregatorKinds,
                    paste = paste,
                    onInvalid = { Toast.makeText(context, invalidKindMsg, Toast.LENGTH_SHORT).show() },
                )
                aggregatorRelayItems(
                    extraRelays = viewModel.extraRelays,
                    sourceRelays = viewModel.sourceRelays,
                    indexerRelays = viewModel.indexerRelays,
                    paste = paste,
                    onInvalid = { Toast.makeText(context, invalidRelayMsg, Toast.LENGTH_SHORT).show() },
                )
            }

            SettingsApplyBar(enabled = !viewModel.isLoading, onApply = { viewModel.applyChanges(onApplyChanges) })
        }
    }
}

@Composable
private fun rememberAggregatorSignerLogin(onLogin: (key: String, packageName: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noSignerInstalledMsg = stringResource(R.string.no_external_signer_installed)

    val aggregatorSignerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(
                context,
                signRequestRejectedMsg,
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            result.data?.let {
                try {
                    val key = it.getStringExtra("signature") ?: ""
                    val packageName = it.getStringExtra("package") ?: ""
                    onLogin(key, packageName)
                } catch (e: Exception) {
                    Log.d(Citrine.TAG, e.message ?: "", e)
                }
            }
        }
    }

    return {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
            intent.putExtra("type", "get_public_key")
            val permission = Permission(CommandType.SIGN_EVENT, 22242)
            intent.putExtra("permissions", "[${permission.toJson()}]")
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            aggregatorSignerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.message ?: "", e)
            Toast.makeText(
                context,
                noSignerInstalledMsg,
                Toast.LENGTH_SHORT,
            ).show()
            val fallback = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
            aggregatorSignerLauncher.launch(fallback)
        }
    }
}

private fun LazyListScope.aggregatorAccountItems(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    pubkey: TextFieldValue,
    onPubkeyChange: (TextFieldValue) -> Unit,
    onSignerLogin: () -> Unit,
    refreshMinutes: TextFieldValue,
    onRefreshMinutesChange: (TextFieldValue) -> Unit,
) {
    item {
        SwitchSettingRow(
            title = stringResource(R.string.relay_aggregator),
            description = stringResource(R.string.relay_aggregator_description),
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = pubkey,
            label = { Text(stringResource(R.string.relay_aggregator_pubkey)) },
            onValueChange = onPubkeyChange,
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onSignerLogin) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = stringResource(R.string.relay_aggregator_signer_login),
                    )
                }
            },
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = refreshMinutes,
            label = { Text(stringResource(R.string.relay_aggregator_refresh_minutes)) },
            onValueChange = onRefreshMinutesChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

private fun LazyListScope.aggregatorNetworkItems(
    includeTagged: Boolean,
    onIncludeTaggedChange: (Boolean) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    pauseOnLimitedNetwork: Boolean,
    onPauseOnLimitedNetworkChange: (Boolean) -> Unit,
) {
    item {
        SwitchSettingRow(
            title = stringResource(R.string.relay_aggregator_include_tagged),
            description = stringResource(R.string.relay_aggregator_include_tagged_description),
            checked = includeTagged,
            onCheckedChange = onIncludeTaggedChange,
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.relay_aggregator_wifi_only),
            description = stringResource(R.string.relay_aggregator_wifi_only_description),
            checked = wifiOnly,
            onCheckedChange = onWifiOnlyChange,
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.relay_aggregator_pause_on_limited_network),
            description = stringResource(R.string.relay_aggregator_pause_on_limited_network_description),
            checked = pauseOnLimitedNetwork,
            onCheckedChange = onPauseOnLimitedNetworkChange,
        )
    }
}

private fun LazyListScope.aggregatorKindItems(
    kinds: EditableSet<Int>,
    paste: (onText: (String) -> Unit) -> Unit,
    onInvalid: () -> Unit,
) {
    item {
        PubkeyInputRow(
            value = kinds.input,
            onValueChange = { kinds.input = it },
            onPaste = { paste { if (!kinds.paste(it)) onInvalid() } },
            onAdd = { if (!kinds.add()) onInvalid() },
        )
    }
    item {
        TextButton(onClick = { kinds.reset(Settings.DEFAULT_AGGREGATOR_KINDS) }) {
            Text(stringResource(R.string.relay_aggregator_reset_defaults))
        }
    }
    if (kinds.values.isEmpty()) {
        item {
            EmptyListHint(stringResource(R.string.relay_aggregator_kinds_empty_hint))
        }
    }
    items(kinds.values.toList()) { k ->
        PubkeyListItem(
            text = k.toString(),
            onDelete = { kinds.remove(k) },
        )
    }
}

private fun LazyListScope.aggregatorRelayItems(
    extraRelays: EditableSet<String>,
    sourceRelays: EditableSet<String>,
    indexerRelays: EditableSet<String>,
    paste: (onText: (String) -> Unit) -> Unit,
    onInvalid: () -> Unit,
) {
    relayListItems(
        title = R.string.relay_aggregator_extra_relays,
        description = R.string.relay_aggregator_extra_relays_description,
        relays = extraRelays,
        paste = paste,
        onInvalid = onInvalid,
        emptyHint = R.string.relay_aggregator_extra_relays_hint,
    )
    relayListItems(
        title = R.string.relay_aggregator_source_relays,
        description = R.string.relay_aggregator_source_relays_description,
        relays = sourceRelays,
        paste = paste,
        onInvalid = onInvalid,
        defaults = Settings.DEFAULT_AGGREGATOR_SOURCE_RELAYS,
    )
    relayListItems(
        title = R.string.relay_aggregator_indexer_relays,
        description = R.string.relay_aggregator_indexer_relays_description,
        relays = indexerRelays,
        paste = paste,
        onInvalid = onInvalid,
        defaults = Settings.DEFAULT_NIP65_INDEXER_RELAYS,
    )
}

private fun LazyListScope.relayListItems(
    @StringRes title: Int,
    @StringRes description: Int,
    relays: EditableSet<String>,
    paste: (onText: (String) -> Unit) -> Unit,
    onInvalid: () -> Unit,
    defaults: Set<String>? = null,
    @StringRes emptyHint: Int? = null,
) {
    item {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    item {
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    item {
        PubkeyInputRow(
            value = relays.input,
            onValueChange = { relays.input = it },
            onPaste = { paste { relays.input = TextFieldValue(it) } },
            onAdd = { if (!relays.add()) onInvalid() },
        )
    }
    if (defaults != null) {
        item {
            TextButton(onClick = { relays.reset(defaults) }) {
                Text(stringResource(R.string.relay_aggregator_reset_defaults))
            }
        }
    }
    if (emptyHint != null && relays.values.isEmpty()) {
        item {
            EmptyListHint(stringResource(emptyHint))
        }
    }
    items(relays.values.toList()) { relay ->
        PubkeyListItem(
            text = relay,
            onDelete = { relays.remove(relay) },
        )
    }
}
