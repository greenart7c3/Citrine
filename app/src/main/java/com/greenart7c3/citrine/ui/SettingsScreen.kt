@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui

import android.content.Intent
import android.net.InetAddresses.isNumericAddress
import android.os.Build
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anggrayudi.storage.SimpleStorageHelper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayAggregator
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SettingsRow
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import com.greenart7c3.citrine.ui.components.TitleExplainer
import com.greenart7c3.citrine.ui.components.WebAppInput
import com.greenart7c3.citrine.ui.components.WebAppRow
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NProfile
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    storageHelper: SimpleStorageHelper,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(modifier) {
        val clipboardManager = LocalClipboard.current
        val context = LocalContext.current

        var host by remember { mutableStateOf(TextFieldValue(Settings.host)) }
        var port by remember { mutableStateOf(TextFieldValue(Settings.port.toString())) }
        var relayName by remember { mutableStateOf(TextFieldValue(Settings.name)) }
        var relayOwnerPubkey by remember { mutableStateOf(TextFieldValue(Settings.ownerPubkey)) }
        var relayContact by remember { mutableStateOf(TextFieldValue(Settings.contact)) }
        var relayDescription by remember { mutableStateOf(TextFieldValue(Settings.description)) }
        var relayIconUrl by remember { mutableStateOf(TextFieldValue(Settings.relayIcon)) }

        var startOnBoot by remember { mutableStateOf(Settings.startOnBoot) }
        var useAuth by remember { mutableStateOf(Settings.authEnabled) }
        var listenToPokeyBroadcasts by remember { mutableStateOf(Settings.listenToPokeyBroadcasts) }
        var useProxy by remember { mutableStateOf(Settings.useProxy) }
        var proxyAllUrls by remember { mutableStateOf(Settings.proxyAllUrls) }
        var useTor by remember { mutableStateOf(Settings.useTor) }
        val torState = com.greenart7c3.citrine.service.TorManager.state.collectAsStateWithLifecycle()
        var autoBackup by remember { mutableStateOf(Settings.autoBackup) }
        var autoBackupFolder by remember { mutableStateOf(Settings.autoBackupFolder) }

        var signedBy by remember { mutableStateOf(TextFieldValue("")) }
        var referredBy by remember { mutableStateOf(TextFieldValue("")) }
        var kind by remember { mutableStateOf(TextFieldValue("")) }
        var deleteFrom by remember { mutableStateOf(TextFieldValue("")) }

        var allowedPubKeys by remember { mutableStateOf(Settings.allowedPubKeys) }
        var allowedTaggedPubKeys by remember { mutableStateOf(Settings.allowedTaggedPubKeys) }
        var allowedKinds by remember { mutableStateOf(Settings.allowedKinds) }
        var neverDeleteFrom by remember { mutableStateOf(Settings.neverDeleteFrom) }
        var preservedKindsFromDeletion by remember { mutableStateOf(Settings.preservedKindsFromDeletion) }
        var preservedKindInput by remember { mutableStateOf(TextFieldValue("")) }

        var relayAggregatorEnabled by remember { mutableStateOf(Settings.relayAggregatorEnabled) }
        var aggregatorPubkey by remember { mutableStateOf(TextFieldValue(Settings.aggregatorPubkey)) }
        var aggregatorKinds by remember { mutableStateOf(Settings.relayAggregatorKinds) }
        var aggregatorKindInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorRefreshMinutes by remember {
            mutableStateOf(TextFieldValue(Settings.relayAggregatorRefreshMinutes.toString()))
        }
        var aggregatorIncludeTagged by remember { mutableStateOf(Settings.relayAggregatorIncludeTagged) }
        var aggregatorWifiOnly by remember { mutableStateOf(Settings.relayAggregatorWifiOnly) }
        var aggregatorExtraRelays by remember { mutableStateOf(Settings.relayAggregatorExtraRelays) }
        var aggregatorExtraRelayInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorSignerPubkey by remember { mutableStateOf(Settings.aggregatorSignerPubkey) }
        var aggregatorSignerPackageName by remember { mutableStateOf(Settings.aggregatorSignerPackageName) }

        var olderThanTypeIndex by remember {
            mutableIntStateOf(
                OlderThanType.entries.toTypedArray()
                    .indexOfFirst { it.name == Settings.deleteEventsOlderThan.toString() },
            )
        }

        val aggregatorSignerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) {
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

        var shouldAddWebClient = false
        var webPath by remember { mutableStateOf(TextFieldValue("")) }
        var webClients by remember { mutableStateOf<Map<String, String>>(Settings.webClients.toMap()) }

        storageHelper.onFolderSelected = { _, folder ->
            if (shouldAddWebClient) {
                val path = if (webPath.text.startsWith("/")) webPath.text else "/${webPath.text}"
                webClients = webClients + (path to folder.uri.toString())
                webPath = TextFieldValue("")
            } else {
                autoBackup = true
                autoBackupFolder = folder.uri.toString()
            }
        }

        val resetToDefaults: () -> Unit = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.defaultValues()
                host = TextFieldValue(Settings.host)
                port = TextFieldValue(Settings.port.toString())
                relayName = TextFieldValue(Settings.name)
                relayOwnerPubkey = TextFieldValue(Settings.ownerPubkey)
                relayContact = TextFieldValue(Settings.contact)
                relayDescription = TextFieldValue(Settings.description)
                relayIconUrl = TextFieldValue(Settings.relayIcon)
                startOnBoot = Settings.startOnBoot
                useAuth = Settings.authEnabled
                listenToPokeyBroadcasts = Settings.listenToPokeyBroadcasts
                useProxy = Settings.useProxy
                proxyAllUrls = Settings.proxyAllUrls
                useTor = Settings.useTor
                autoBackup = Settings.autoBackup
                autoBackupFolder = Settings.autoBackupFolder
                signedBy = TextFieldValue("")
                referredBy = TextFieldValue("")
                kind = TextFieldValue("")
                deleteFrom = TextFieldValue("")
                preservedKindInput = TextFieldValue("")
                allowedPubKeys = Settings.allowedPubKeys
                allowedTaggedPubKeys = Settings.allowedTaggedPubKeys
                allowedKinds = Settings.allowedKinds
                neverDeleteFrom = Settings.neverDeleteFrom
                preservedKindsFromDeletion = Settings.preservedKindsFromDeletion
                relayAggregatorEnabled = Settings.relayAggregatorEnabled
                aggregatorPubkey = TextFieldValue(Settings.aggregatorPubkey)
                aggregatorKinds = Settings.relayAggregatorKinds
                aggregatorKindInput = TextFieldValue("")
                aggregatorRefreshMinutes = TextFieldValue(Settings.relayAggregatorRefreshMinutes.toString())
                aggregatorIncludeTagged = Settings.relayAggregatorIncludeTagged
                aggregatorWifiOnly = Settings.relayAggregatorWifiOnly
                aggregatorExtraRelays = Settings.relayAggregatorExtraRelays
                aggregatorExtraRelayInput = TextFieldValue("")
                aggregatorSignerPubkey = Settings.aggregatorSignerPubkey
                aggregatorSignerPackageName = Settings.aggregatorSignerPackageName
                webClients = Settings.webClients.toMap()
                olderThanTypeIndex = OlderThanType.entries.toTypedArray()
                    .indexOfFirst { it.name == Settings.deleteEventsOlderThan.toString() }
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                if (Settings.listenToPokeyBroadcasts) {
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

        val applyChanges: () -> Unit = {
            if (!isIpValid(host.text) || host.text.isBlank()) {
                Toast.makeText(context, context.getString(R.string.invalid_host_or_port), Toast.LENGTH_SHORT).show()
            } else if (port.text.isBlank() || !port.text.isDigitsOnly()) {
                Toast.makeText(context, context.getString(R.string.invalid_host_or_port), Toast.LENGTH_SHORT).show()
            } else if (relayOwnerPubkey.text.isNotBlank() && relayOwnerPubkey.text.toNostrKey() == null) {
                Toast.makeText(context, context.getString(R.string.invalid_owner_pubkey), Toast.LENGTH_SHORT).show()
            } else if (relayIconUrl.text.isNotBlank() && runCatching { relayIconUrl.text.toUri() }.isFailure) {
                Toast.makeText(context, context.getString(R.string.invalid_icon_url), Toast.LENGTH_SHORT).show()
            } else {
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
                    Settings.host = host.text
                    Settings.port = port.text.toInt()
                    Settings.name = relayName.text
                    Settings.ownerPubkey = relayOwnerPubkey.text.toNostrKey() ?: ""
                    Settings.contact = relayContact.text
                    Settings.description = relayDescription.text
                    Settings.relayIcon = relayIconUrl.text
                    Settings.startOnBoot = startOnBoot
                    Settings.authEnabled = useAuth
                    Settings.listenToPokeyBroadcasts = listenToPokeyBroadcasts
                    Settings.useProxy = useProxy
                    Settings.proxyAllUrls = proxyAllUrls
                    Settings.useTor = useTor
                    Settings.autoBackup = autoBackup
                    Settings.autoBackupFolder = autoBackupFolder
                    Settings.allowedPubKeys = allowedPubKeys
                    Settings.allowedTaggedPubKeys = allowedTaggedPubKeys
                    Settings.allowedKinds = allowedKinds
                    Settings.neverDeleteFrom = neverDeleteFrom
                    Settings.preservedKindsFromDeletion = preservedKindsFromDeletion
                    Settings.relayAggregatorEnabled = relayAggregatorEnabled
                    Settings.aggregatorPubkey = resolvedAggregatorKey
                    Settings.aggregatorSignerPubkey = resolvedSignerPubkey
                    Settings.aggregatorSignerPackageName = resolvedSignerPackage
                    Settings.relayAggregatorKinds = aggregatorKinds
                    Settings.relayAggregatorRefreshMinutes = refreshMinutes
                    Settings.relayAggregatorIncludeTagged = aggregatorIncludeTagged
                    Settings.relayAggregatorWifiOnly = aggregatorWifiOnly
                    Settings.relayAggregatorExtraRelays = aggregatorExtraRelays
                    Settings.deleteEventsOlderThan = when (olderThanTypeIndex) {
                        OlderThanType.NEVER.screenCode -> OlderThan.NEVER
                        OlderThanType.DAY.screenCode -> OlderThan.DAY
                        OlderThanType.WEEK.screenCode -> OlderThan.WEEK
                        OlderThanType.MONTH.screenCode -> OlderThan.MONTH
                        OlderThanType.YEAR.screenCode -> OlderThan.YEAR
                        else -> OlderThan.NEVER
                    }
                    Settings.webClients = webClients.toMutableMap()
                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)

                    aggregatorPubkey = TextFieldValue(resolvedAggregatorKey)
                    aggregatorSignerPubkey = resolvedSignerPubkey
                    aggregatorSignerPackageName = resolvedSignerPackage
                    aggregatorRefreshMinutes = TextFieldValue(refreshMinutes.toString())

                    if (listenToPokeyBroadcasts) {
                        Citrine.instance.registerPokeyReceiver()
                    } else {
                        Citrine.instance.unregisterPokeyReceiver()
                    }
                    RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                    onApplyChanges()
                    delay(1500)
                    isLoading = false
                }
                Unit
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                // ── Server ─────────────────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.server))
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = host,
                        label = { Text(stringResource(R.string.host)) },
                        onValueChange = { host = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = port,
                        label = { Text(stringResource(R.string.port)) },
                        onValueChange = { port = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = relayName,
                        label = { Text(stringResource(R.string.relay_name)) },
                        onValueChange = { relayName = it },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = relayOwnerPubkey,
                        label = { Text(stringResource(R.string.relay_owner_pubkey)) },
                        onValueChange = { relayOwnerPubkey = it },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = relayContact,
                        label = { Text(stringResource(R.string.relay_contact)) },
                        onValueChange = { relayContact = it },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = relayDescription,
                        label = { Text(stringResource(R.string.relay_description)) },
                        onValueChange = { relayDescription = it },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        value = relayIconUrl,
                        label = { Text(stringResource(R.string.relay_icon_url)) },
                        onValueChange = { relayIconUrl = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }

                // ── Accept events signed by ────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.accept_events_signed_by))
                }
                item {
                    PubkeyInputRow(
                        value = signedBy,
                        onValueChange = { signedBy = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                signedBy = TextFieldValue(text)
                                val key = text.toNostrKey()
                                if (key == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                allowedPubKeys = allowedPubKeys + key
                                signedBy = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val key = signedBy.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            allowedPubKeys = allowedPubKeys + key
                            signedBy = TextFieldValue("")
                        },
                    )
                }
                if (allowedPubKeys.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.allow_all_pubkeys_hint))
                    }
                }
                items(allowedPubKeys.toList()) { pubkey ->
                    PubkeyListItem(
                        text = pubkey.toShortenHex(),
                        onDelete = {
                            allowedPubKeys = allowedPubKeys - pubkey
                        },
                    )
                }

                // ── Accept events that refer to ────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.accept_events_that_refer_to))
                }
                item {
                    PubkeyInputRow(
                        value = referredBy,
                        onValueChange = { referredBy = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                referredBy = TextFieldValue(text)
                                val key = text.toNostrKey()
                                if (key == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                allowedTaggedPubKeys = allowedTaggedPubKeys + key
                                referredBy = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val key = referredBy.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            allowedTaggedPubKeys = allowedTaggedPubKeys + key
                            referredBy = TextFieldValue("")
                        },
                    )
                }
                if (allowedTaggedPubKeys.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.allow_all_tagged_pubkeys_hint))
                    }
                }
                items(allowedTaggedPubKeys.toList()) { pubkey ->
                    PubkeyListItem(
                        text = pubkey.toShortenHex(),
                        onDelete = {
                            allowedTaggedPubKeys = allowedTaggedPubKeys - pubkey
                        },
                    )
                }

                // ── Allowed Kinds ──────────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.allowed_kinds))
                }
                item {
                    PubkeyInputRow(
                        value = kind,
                        onValueChange = { kind = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                kind = TextFieldValue(text)
                                val k = text.toIntOrNull()
                                if (k == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                allowedKinds = allowedKinds + k
                                kind = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val k = kind.text.toIntOrNull()
                            if (k == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            allowedKinds = allowedKinds + k
                            kind = TextFieldValue("")
                        },
                    )
                }
                if (allowedKinds.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.allow_all_kinds_hint))
                    }
                }
                items(allowedKinds.toList()) { k ->
                    PubkeyListItem(
                        text = k.toString(),
                        onDelete = {
                            allowedKinds = allowedKinds - k
                        },
                    )
                }

                // ── Relay Aggregator ───────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.relay_aggregator))
                }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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
                                        val permission = com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission(
                                            com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType.SIGN_EVENT,
                                            22242,
                                        )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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
                        onDelete = {
                            aggregatorKinds = aggregatorKinds - k
                        },
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
                        onDelete = {
                            aggregatorExtraRelays = aggregatorExtraRelays - relay
                        },
                    )
                }

                // ── Others ─────────────────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.others))
                }
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
                        title = stringResource(R.string.enable_disable_auth),
                        description = stringResource(R.string.enable_disable_auth_description),
                        checked = useAuth,
                        onCheckedChange = { useAuth = it },
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
                        is com.greenart7c3.citrine.service.TorManager.State.Running -> s.hostname
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
                item {
                    val deleteItems = persistentListOf(
                        TitleExplainer(stringResource(OlderThanType.NEVER.resourceId)),
                        TitleExplainer(stringResource(OlderThanType.DAY.resourceId)),
                        TitleExplainer(stringResource(OlderThanType.WEEK.resourceId)),
                        TitleExplainer(stringResource(OlderThanType.MONTH.resourceId)),
                        TitleExplainer(stringResource(OlderThanType.YEAR.resourceId)),
                    )
                    SettingsRow(
                        name = R.string.delete_events_older_than,
                        description = R.string.delete_events_older_than_description,
                        selectedItems = deleteItems,
                        selectedIndex = olderThanTypeIndex,
                    ) {
                        olderThanTypeIndex = it
                    }
                }

                // ── Never delete from ──────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.never_delete_from))
                }
                item {
                    PubkeyInputRow(
                        value = deleteFrom,
                        onValueChange = { deleteFrom = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                deleteFrom = TextFieldValue(text)
                                val key = text.toNostrKey()
                                if (key == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                neverDeleteFrom = neverDeleteFrom + key
                                deleteFrom = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val key = deleteFrom.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            neverDeleteFrom = neverDeleteFrom + key
                            deleteFrom = TextFieldValue("")
                        },
                    )
                }
                items(neverDeleteFrom.toList()) { pubkey ->
                    PubkeyListItem(
                        text = pubkey.toShortenHex(),
                        onDelete = {
                            neverDeleteFrom = neverDeleteFrom - pubkey
                        },
                    )
                }

                // ── Preserved kinds ────────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.preserved_kinds_from_deletion))
                }
                item {
                    EmptyListHint(stringResource(R.string.preserved_kinds_from_deletion_description))
                }
                item {
                    PubkeyInputRow(
                        value = preservedKindInput,
                        onValueChange = { preservedKindInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                preservedKindInput = TextFieldValue(text)
                                val k = text.toIntOrNull()
                                if (k == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                preservedKindsFromDeletion = preservedKindsFromDeletion + k
                                preservedKindInput = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val k = preservedKindInput.text.toIntOrNull()
                            if (k == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            preservedKindsFromDeletion = preservedKindsFromDeletion + k
                            preservedKindInput = TextFieldValue("")
                        },
                    )
                }
                items(preservedKindsFromDeletion.toList()) { k ->
                    PubkeyListItem(
                        text = k.toString(),
                        onDelete = {
                            preservedKindsFromDeletion = preservedKindsFromDeletion - k
                        },
                    )
                }

                // ── Backup ─────────────────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.backup))
                }
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.auto_backup),
                        description = stringResource(R.string.auto_backup_description),
                        checked = autoBackup,
                        onCheckedChange = {
                            if (!autoBackup) {
                                shouldAddWebClient = false
                                storageHelper.openFolderPicker()
                            } else {
                                autoBackup = false
                            }
                        },
                    )
                }

                // ── Web Clients ────────────────────────────────────────────────────
                stickyHeader {
                    SectionHeader(stringResource(R.string.web_clients))
                }
                item {
                    val nameNotBlankResource = stringResource(R.string.name_cannot_be_blank)
                    WebAppInput(
                        value = webPath,
                        onValueChange = { webPath = it },
                        onAdd = {
                            if (webPath.text.isBlank()) {
                                Toast.makeText(context, nameNotBlankResource, Toast.LENGTH_SHORT).show()
                                return@WebAppInput
                            }
                            shouldAddWebClient = true
                            storageHelper.openFolderPicker()
                        },
                    )
                }
                items(webClients.keys.toList()) { item ->
                    WebAppRow(
                        onOpenWebApp = {
                            val browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                "http://${item.removePrefix("/")}.localhost:${Settings.port}".toUri(),
                            )
                            browserIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            Citrine.instance.startActivity(browserIntent)
                        },
                        onDelete = {
                            webClients = webClients - item
                        },
                        item = item,
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                ElevatedButton(
                    enabled = !isLoading,
                    onClick = {
                        if (isLoading) return@ElevatedButton
                        resetToDefaults()
                    },
                ) {
                    Text(stringResource(R.string.default_value))
                }

                ElevatedButton(
                    enabled = !isLoading,
                    onClick = {
                        if (isLoading) return@ElevatedButton
                        applyChanges()
                    },
                ) {
                    Text(stringResource(R.string.apply_changes))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        HorizontalDivider()
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun EmptyListHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

fun String.toShortenHex(): String {
    if (length <= 16) return this
    return "${take(8)}...${takeLast(8)}"
}

fun isIpValid(ip: String): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    isNumericAddress(ip)
} else {
    @Suppress("DEPRECATION")
    Patterns.IP_ADDRESS.matcher(ip).matches()
}

fun normalizeRelayInput(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> trimmed
        "://" in trimmed -> return null
        else -> "wss://$trimmed"
    }
    // Reject schemes we don't speak and anything without a host.
    val afterScheme = withScheme.substringAfter("://")
    if (afterScheme.isEmpty() || afterScheme.startsWith("/")) return null
    return withScheme
}

fun String.toNostrKey(): String? {
    val key = try {
        Hex.decode(this)
    } catch (_: Exception) {
        null
    }
    if (key != null) {
        return this
    }

    val pubKeyParsed =
        when (val parsed = Nip19Parser.uriToRoute(this)?.entity) {
            is NPub -> parsed.hex.hexToByteArray()
            is NProfile -> parsed.hex.hexToByteArray()
            else -> null
        }
    return pubKeyParsed?.toHexKey()
}
