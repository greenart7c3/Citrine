package com.greenart7c3.citrine.ui.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AggregatorSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current

    Surface(modifier) {
        var relayAggregatorEnabled by remember { mutableStateOf(Settings.relayAggregatorEnabled) }
        var aggregatorPubkey by remember { mutableStateOf(TextFieldValue(Settings.aggregatorPubkey)) }
        var aggregatorKinds by remember { mutableStateOf(Settings.relayAggregatorKinds) }
        var aggregatorKindInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorRefreshMinutes by remember {
            mutableStateOf(TextFieldValue(Settings.relayAggregatorRefreshMinutes.toString()))
        }
        var aggregatorIncludeTagged by remember { mutableStateOf(Settings.relayAggregatorIncludeTagged) }
        var aggregatorWifiOnly by remember { mutableStateOf(Settings.relayAggregatorWifiOnly) }
        var aggregatorPauseOnLimitedNetwork by remember { mutableStateOf(Settings.relayAggregatorPauseOnLimitedNetwork) }
        var aggregatorExtraRelays by remember { mutableStateOf(Settings.relayAggregatorExtraRelays) }
        var aggregatorExtraRelayInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorSourceRelays by remember { mutableStateOf(Settings.relayAggregatorSourceRelays) }
        var aggregatorSourceRelayInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorIndexerRelays by remember { mutableStateOf(Settings.relayAggregatorIndexerRelays) }
        var aggregatorIndexerRelayInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorSignerPubkey by remember { mutableStateOf(Settings.aggregatorSignerPubkey) }
        var aggregatorSignerPackageName by remember { mutableStateOf(Settings.aggregatorSignerPackageName) }

        val aggregatorSignerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                Toast.makeText(
                    context,
                    context.getString(R.string.sign_request_rejected),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                result.data?.let {
                    try {
                        val key = it.getStringExtra("signature") ?: ""
                        val packageName = it.getStringExtra("package") ?: ""
                        val returnedKey = if (key.startsWith("npub")) {
                            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                                is NPub -> parsed.hex
                                else -> ""
                            }
                        } else {
                            key
                        }
                        if (returnedKey.isBlank() || packageName.isBlank()) return@let
                        aggregatorPubkey = TextFieldValue(returnedKey)
                        aggregatorSignerPubkey = returnedKey
                        aggregatorSignerPackageName = packageName
                    } catch (e: Exception) {
                        android.util.Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        }

        val applyChanges = {
            val refreshMinutes = aggregatorRefreshMinutes.text.toIntOrNull()?.takeIf { it >= 1 }
                ?: Settings.relayAggregatorRefreshMinutes
            val aggregatorKeyTrimmed = aggregatorPubkey.text.trim()
            val resolvedAggregatorKey = if (aggregatorKeyTrimmed.isBlank()) {
                ""
            } else {
                aggregatorKeyTrimmed.toNostrKey() ?: aggregatorKeyTrimmed
            }
            val resolvedSignerPubkey: String
            val resolvedSignerPackage: String
            if (resolvedAggregatorKey.isBlank()) {
                resolvedSignerPubkey = ""
                resolvedSignerPackage = ""
            } else if (resolvedAggregatorKey != aggregatorSignerPubkey) {
                resolvedSignerPubkey = ""
                resolvedSignerPackage = ""
            } else {
                resolvedSignerPubkey = aggregatorSignerPubkey
                resolvedSignerPackage = aggregatorSignerPackageName
            }

            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.relayAggregatorEnabled = relayAggregatorEnabled
                Settings.aggregatorPubkey = resolvedAggregatorKey
                Settings.aggregatorSignerPubkey = resolvedSignerPubkey
                Settings.aggregatorSignerPackageName = resolvedSignerPackage
                Settings.relayAggregatorKinds = aggregatorKinds
                Settings.relayAggregatorRefreshMinutes = refreshMinutes
                Settings.relayAggregatorIncludeTagged = aggregatorIncludeTagged
                Settings.relayAggregatorWifiOnly = aggregatorWifiOnly
                Settings.relayAggregatorPauseOnLimitedNetwork = aggregatorPauseOnLimitedNetwork
                Settings.relayAggregatorExtraRelays = aggregatorExtraRelays
                Settings.relayAggregatorSourceRelays = aggregatorSourceRelays
                Settings.relayAggregatorIndexerRelays = aggregatorIndexerRelays
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)

                aggregatorPubkey = TextFieldValue(resolvedAggregatorKey)
                aggregatorSignerPubkey = resolvedSignerPubkey
                aggregatorSignerPackageName = resolvedSignerPackage
                aggregatorRefreshMinutes = TextFieldValue(refreshMinutes.toString())

                RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
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
                        title = stringResource(R.string.relay_aggregator),
                        description = stringResource(R.string.relay_aggregator_description),
                        checked = relayAggregatorEnabled,
                        onCheckedChange = {
                            if (it && aggregatorPubkey.text.isBlank() && aggregatorExtraRelays.isEmpty()) {
                                Toast.makeText(context, context.getString(R.string.relay_aggregator_pubkey_or_relays_required), Toast.LENGTH_SHORT).show()
                                return@SwitchSettingRow
                            }
                            relayAggregatorEnabled = it
                        },
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = aggregatorPubkey,
                        label = { Text(stringResource(R.string.relay_aggregator_pubkey)) },
                        onValueChange = { aggregatorPubkey = it },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
                                        intent.putExtra("type", "get_public_key")
                                        val permission = Permission(CommandType.SIGN_EVENT, 22242)
                                        intent.putExtra("permissions", "[${permission.toJson()}]")
                                        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        aggregatorSignerLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        android.util.Log.d(Citrine.TAG, e.message ?: "", e)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.no_external_signer_installed),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        val fallback = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
                                        aggregatorSignerLauncher.launch(fallback)
                                    }
                                },
                            ) {
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
                        value = aggregatorRefreshMinutes,
                        label = { Text(stringResource(R.string.relay_aggregator_refresh_minutes)) },
                        onValueChange = { aggregatorRefreshMinutes = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.relay_aggregator_include_tagged),
                        description = stringResource(R.string.relay_aggregator_include_tagged_description),
                        checked = aggregatorIncludeTagged,
                        onCheckedChange = { aggregatorIncludeTagged = it },
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.relay_aggregator_wifi_only),
                        description = stringResource(R.string.relay_aggregator_wifi_only_description),
                        checked = aggregatorWifiOnly,
                        onCheckedChange = { aggregatorWifiOnly = it },
                    )
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.relay_aggregator_pause_on_limited_network),
                        description = stringResource(R.string.relay_aggregator_pause_on_limited_network_description),
                        checked = aggregatorPauseOnLimitedNetwork,
                        onCheckedChange = { aggregatorPauseOnLimitedNetwork = it },
                    )
                }
                item {
                    PubkeyInputRow(
                        value = aggregatorKindInput,
                        onValueChange = { aggregatorKindInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                aggregatorKindInput = TextFieldValue(text)
                                val k = text.toIntOrNull()
                                if (k == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                aggregatorKinds = aggregatorKinds + k
                                aggregatorKindInput = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val k = aggregatorKindInput.text.toIntOrNull()
                            if (k == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            aggregatorKinds = aggregatorKinds + k
                            aggregatorKindInput = TextFieldValue("")
                        },
                    )
                }
                if (aggregatorKinds.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.relay_aggregator_kinds_empty_hint))
                    }
                }
                items(aggregatorKinds.toList()) { k ->
                    PubkeyListItem(
                        text = k.toString(),
                        onDelete = { aggregatorKinds = aggregatorKinds - k },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.relay_aggregator_extra_relays),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.relay_aggregator_extra_relays_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                item {
                    PubkeyInputRow(
                        value = aggregatorExtraRelayInput,
                        onValueChange = { aggregatorExtraRelayInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                aggregatorExtraRelayInput = TextFieldValue(text)
                            }
                        },
                        onAdd = {
                            val normalized = normalizeRelayInput(aggregatorExtraRelayInput.text)
                            if (normalized == null) {
                                Toast.makeText(context, context.getString(R.string.relay_aggregator_invalid_relay), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            aggregatorExtraRelays = aggregatorExtraRelays + normalized
                            aggregatorExtraRelayInput = TextFieldValue("")
                        },
                    )
                }
                if (aggregatorExtraRelays.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.relay_aggregator_extra_relays_hint))
                    }
                }
                items(aggregatorExtraRelays.toList()) { relay ->
                    PubkeyListItem(
                        text = relay,
                        onDelete = { aggregatorExtraRelays = aggregatorExtraRelays - relay },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.relay_aggregator_source_relays),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.relay_aggregator_source_relays_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                item {
                    PubkeyInputRow(
                        value = aggregatorSourceRelayInput,
                        onValueChange = { aggregatorSourceRelayInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                aggregatorSourceRelayInput = TextFieldValue(text)
                            }
                        },
                        onAdd = {
                            val normalized = normalizeRelayInput(aggregatorSourceRelayInput.text)
                            if (normalized == null) {
                                Toast.makeText(context, context.getString(R.string.relay_aggregator_invalid_relay), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            aggregatorSourceRelays = aggregatorSourceRelays + normalized
                            aggregatorSourceRelayInput = TextFieldValue("")
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = { aggregatorSourceRelays = Settings.DEFAULT_AGGREGATOR_SOURCE_RELAYS },
                    ) {
                        Text(stringResource(R.string.relay_aggregator_reset_defaults))
                    }
                }
                items(aggregatorSourceRelays.toList()) { relay ->
                    PubkeyListItem(
                        text = relay,
                        onDelete = { aggregatorSourceRelays = aggregatorSourceRelays - relay },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.relay_aggregator_indexer_relays),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.relay_aggregator_indexer_relays_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                item {
                    PubkeyInputRow(
                        value = aggregatorIndexerRelayInput,
                        onValueChange = { aggregatorIndexerRelayInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                aggregatorIndexerRelayInput = TextFieldValue(text)
                            }
                        },
                        onAdd = {
                            val normalized = normalizeRelayInput(aggregatorIndexerRelayInput.text)
                            if (normalized == null) {
                                Toast.makeText(context, context.getString(R.string.relay_aggregator_invalid_relay), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            aggregatorIndexerRelays = aggregatorIndexerRelays + normalized
                            aggregatorIndexerRelayInput = TextFieldValue("")
                        },
                    )
                }
                item {
                    TextButton(
                        onClick = { aggregatorIndexerRelays = Settings.DEFAULT_NIP65_INDEXER_RELAYS },
                    ) {
                        Text(stringResource(R.string.relay_aggregator_reset_defaults))
                    }
                }
                items(aggregatorIndexerRelays.toList()) { relay ->
                    PubkeyListItem(
                        text = relay,
                        onDelete = { aggregatorIndexerRelays = aggregatorIndexerRelays - relay },
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
