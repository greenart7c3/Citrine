@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui

import android.content.Intent
import android.net.InetAddresses.isNumericAddress
import android.os.Build
import android.util.Patterns
import android.widget.Toast
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
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
        var deleteExpiredEvents by remember { mutableStateOf(Settings.deleteExpiredEvents) }
        var deleteEphemeralEvents by remember { mutableStateOf(Settings.deleteEphemeralEvents) }
        var useProxy by remember { mutableStateOf(Settings.useProxy) }
        var proxyPort by remember { mutableStateOf(TextFieldValue(Settings.proxyPort.toString())) }
        var autoBackup by remember { mutableStateOf(Settings.autoBackup) }

        var signedBy by remember { mutableStateOf(TextFieldValue("")) }
        var referredBy by remember { mutableStateOf(TextFieldValue("")) }
        var kind by remember { mutableStateOf(TextFieldValue("")) }
        var deleteFrom by remember { mutableStateOf(TextFieldValue("")) }

        var allowedPubKeys by remember { mutableStateOf(Settings.allowedPubKeys) }
        var allowedTaggedPubKeys by remember { mutableStateOf(Settings.allowedTaggedPubKeys) }
        var allowedKinds by remember { mutableStateOf(Settings.allowedKinds) }
        var neverDeleteFrom by remember { mutableStateOf(Settings.neverDeleteFrom) }

        var relayAggregatorEnabled by remember { mutableStateOf(Settings.relayAggregatorEnabled) }
        var aggregatorPubkey by remember { mutableStateOf(TextFieldValue(Settings.aggregatorPubkey)) }
        var aggregatorKinds by remember { mutableStateOf(Settings.relayAggregatorKinds) }
        var aggregatorKindInput by remember { mutableStateOf(TextFieldValue("")) }
        var aggregatorRefreshMinutes by remember {
            mutableStateOf(TextFieldValue(Settings.relayAggregatorRefreshMinutes.toString()))
        }
        var aggregatorIncludeTagged by remember { mutableStateOf(Settings.relayAggregatorIncludeTagged) }

        var shouldAddWebClient = false
        var webPath by remember { mutableStateOf(TextFieldValue("")) }
        val webClients = MutableStateFlow(Settings.webClients.toMutableMap())
        val clients = webClients.collectAsStateWithLifecycle()

        storageHelper.onFolderSelected = { _, folder ->
            if (shouldAddWebClient) {
                val path = if (webPath.text.startsWith("/")) webPath.text else "/${webPath.text}"
                webClients.update { old ->
                    val updated = old.toMutableMap().apply { this[path] = folder.uri.toString() }
                    Settings.webClients = updated
                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    updated
                }
                onApplyChanges()
                webPath = TextFieldValue("")
            } else {
                autoBackup = true
                Settings.autoBackup = true
                Settings.autoBackupFolder = folder.uri.toString()
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            item {
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
                            scope.launch(Dispatchers.IO) {
                                isLoading = true
                                Settings.defaultValues()
                                host = TextFieldValue(Settings.host)
                                port = TextFieldValue(Settings.port.toString())
                                deleteExpiredEvents = Settings.deleteExpiredEvents
                                deleteEphemeralEvents = Settings.deleteEphemeralEvents
                                relayName = TextFieldValue(Settings.name)
                                relayOwnerPubkey = TextFieldValue(Settings.ownerPubkey)
                                relayContact = TextFieldValue(Settings.contact)
                                relayDescription = TextFieldValue(Settings.description)
                                relayIconUrl = TextFieldValue(Settings.relayIcon)
                                signedBy = TextFieldValue("")
                                referredBy = TextFieldValue("")
                                kind = TextFieldValue("")
                                deleteFrom = TextFieldValue("")
                                allowedPubKeys = Settings.allowedPubKeys
                                allowedTaggedPubKeys = Settings.allowedTaggedPubKeys
                                allowedKinds = Settings.allowedKinds
                                neverDeleteFrom = Settings.neverDeleteFrom
                                relayAggregatorEnabled = Settings.relayAggregatorEnabled
                                aggregatorPubkey = TextFieldValue(Settings.aggregatorPubkey)
                                aggregatorKinds = Settings.relayAggregatorKinds
                                aggregatorRefreshMinutes = TextFieldValue(Settings.relayAggregatorRefreshMinutes.toString())
                                aggregatorIncludeTagged = Settings.relayAggregatorIncludeTagged
                                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                                onApplyChanges()
                                delay(1500)
                                isLoading = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.default_value))
                    }

                    ElevatedButton(
                        enabled = !isLoading,
                        onClick = {
                            if (!isIpValid(host.text) || host.text.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.invalid_host_or_port), Toast.LENGTH_SHORT).show()
                                return@ElevatedButton
                            }
                            if (port.text.isBlank() || !port.text.isDigitsOnly()) {
                                Toast.makeText(context, context.getString(R.string.invalid_host_or_port), Toast.LENGTH_SHORT).show()
                                return@ElevatedButton
                            }
                            if (relayOwnerPubkey.text.isNotBlank() && relayOwnerPubkey.text.toNostrKey() == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_owner_pubkey), Toast.LENGTH_SHORT).show()
                                return@ElevatedButton
                            }
                            if (relayIconUrl.text.isNotBlank()) {
                                try {
                                    relayIconUrl.text.toUri()
                                } catch (_: Exception) {
                                    Toast.makeText(context, context.getString(R.string.invalid_icon_url), Toast.LENGTH_SHORT).show()
                                    return@ElevatedButton
                                }
                            }
                            if (isLoading) return@ElevatedButton
                            scope.launch(Dispatchers.IO) {
                                isLoading = true
                                Settings.port = port.text.toInt()
                                Settings.host = host.text
                                Settings.name = relayName.text
                                Settings.ownerPubkey = relayOwnerPubkey.text.toNostrKey() ?: ""
                                Settings.contact = relayContact.text
                                Settings.description = relayDescription.text
                                Settings.relayIcon = relayIconUrl.text
                                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                onApplyChanges()
                                delay(1500)
                                isLoading = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.apply_changes))
                    }
                }
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
                            val users = Settings.allowedPubKeys.toMutableSet().apply { add(key) }
                            Settings.allowedPubKeys = users
                            allowedPubKeys = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            signedBy = TextFieldValue("")
                        }
                    },
                    onAdd = {
                        val key = signedBy.text.toNostrKey()
                        if (key == null) {
                            Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                            return@PubkeyInputRow
                        }
                        val users = Settings.allowedPubKeys.toMutableSet().apply { add(key) }
                        Settings.allowedPubKeys = users
                        allowedPubKeys = users
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                        val users = Settings.allowedPubKeys.toMutableSet().apply { remove(pubkey) }
                        Settings.allowedPubKeys = users
                        allowedPubKeys = users
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                            val users = Settings.allowedTaggedPubKeys.toMutableSet().apply { add(key) }
                            Settings.allowedTaggedPubKeys = users
                            allowedTaggedPubKeys = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            referredBy = TextFieldValue("")
                        }
                    },
                    onAdd = {
                        val key = referredBy.text.toNostrKey()
                        if (key == null) {
                            Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                            return@PubkeyInputRow
                        }
                        val users = Settings.allowedTaggedPubKeys.toMutableSet().apply { add(key) }
                        Settings.allowedTaggedPubKeys = users
                        allowedTaggedPubKeys = users
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                        val users = Settings.allowedTaggedPubKeys.toMutableSet().apply { remove(pubkey) }
                        Settings.allowedTaggedPubKeys = users
                        allowedTaggedPubKeys = users
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                            if (text.toIntOrNull() == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val kinds = Settings.allowedKinds.toMutableSet().apply { add(text.toInt()) }
                            Settings.allowedKinds = kinds
                            allowedKinds = kinds
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            kind = TextFieldValue("")
                        }
                    },
                    onAdd = {
                        if (kind.text.toIntOrNull() == null) {
                            Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                            return@PubkeyInputRow
                        }
                        val kinds = Settings.allowedKinds.toMutableSet().apply { add(kind.text.toInt()) }
                        Settings.allowedKinds = kinds
                        allowedKinds = kinds
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                        val kinds = Settings.allowedKinds.toMutableSet().apply { remove(k) }
                        Settings.allowedKinds = kinds
                        allowedKinds = kinds
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                        if (it && Settings.aggregatorPubkey.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.relay_aggregator_pubkey_required), Toast.LENGTH_SHORT).show()
                            return@SwitchSettingRow
                        }
                        relayAggregatorEnabled = it
                        Settings.relayAggregatorEnabled = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
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
                    onValueChange = { newValue ->
                        aggregatorPubkey = newValue
                        val text = newValue.text.trim()
                        if (text.isBlank()) {
                            Settings.aggregatorPubkey = ""
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                            return@OutlinedTextField
                        }
                        val key = text.toNostrKey()
                        if (key == null) {
                            return@OutlinedTextField
                        }
                        Settings.aggregatorPubkey = key
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                    },
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = aggregatorRefreshMinutes,
                    label = { Text(stringResource(R.string.relay_aggregator_refresh_minutes)) },
                    onValueChange = {
                        aggregatorRefreshMinutes = it
                        val n = it.text.toIntOrNull() ?: return@OutlinedTextField
                        if (n < 1) return@OutlinedTextField
                        Settings.relayAggregatorRefreshMinutes = n
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.relay_aggregator_include_tagged),
                    description = stringResource(R.string.relay_aggregator_include_tagged_description),
                    checked = aggregatorIncludeTagged,
                    onCheckedChange = {
                        aggregatorIncludeTagged = it
                        Settings.relayAggregatorIncludeTagged = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                    },
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
                            val kinds = Settings.relayAggregatorKinds.toMutableSet().apply { add(k) }
                            Settings.relayAggregatorKinds = kinds
                            aggregatorKinds = kinds
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
                            aggregatorKindInput = TextFieldValue("")
                        }
                    },
                    onAdd = {
                        val k = aggregatorKindInput.text.toIntOrNull()
                        if (k == null) {
                            Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                            return@PubkeyInputRow
                        }
                        val kinds = Settings.relayAggregatorKinds.toMutableSet().apply { add(k) }
                        Settings.relayAggregatorKinds = kinds
                        aggregatorKinds = kinds
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
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
                        val kinds = Settings.relayAggregatorKinds.toMutableSet().apply { remove(k) }
                        Settings.relayAggregatorKinds = kinds
                        aggregatorKinds = kinds
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        RelayAggregator.onConfigChanged(AppDatabase.getDatabase(context))
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
                    onCheckedChange = {
                        startOnBoot = it
                        Settings.startOnBoot = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    },
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.enable_disable_auth),
                    description = stringResource(R.string.enable_disable_auth_description),
                    checked = useAuth,
                    onCheckedChange = {
                        useAuth = it
                        Settings.authEnabled = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    },
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.listen_to_pokey_broadcasts),
                    description = stringResource(R.string.listen_to_pokey_broadcasts_description),
                    checked = listenToPokeyBroadcasts,
                    onCheckedChange = {
                        listenToPokeyBroadcasts = it
                        Settings.listenToPokeyBroadcasts = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        if (it) {
                            Citrine.instance.registerPokeyReceiver()
                        } else {
                            Citrine.instance.unregisterPokeyReceiver()
                        }
                    },
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.delete_expired_events),
                    description = stringResource(R.string.delete_expired_events_description),
                    checked = deleteExpiredEvents,
                    onCheckedChange = {
                        deleteExpiredEvents = it
                        Settings.deleteExpiredEvents = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    },
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.delete_ephemeral_events),
                    description = stringResource(R.string.delete_ephemeral_events_description),
                    checked = deleteEphemeralEvents,
                    onCheckedChange = {
                        deleteEphemeralEvents = it
                        Settings.deleteEphemeralEvents = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    },
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.use_proxy),
                    description = stringResource(R.string.use_proxy_description),
                    checked = useProxy,
                    onCheckedChange = {
                        useProxy = it
                        Settings.useProxy = it
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    },
                )
            }
            if (useProxy) {
                item {
                    OutlinedTextField(
                        proxyPort,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onValueChange = {
                            proxyPort = it
                            if (it.text.toIntOrNull() == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                                return@OutlinedTextField
                            }
                            Settings.proxyPort = it.text.toInt()
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                        label = { Text(stringResource(R.string.proxy_port)) },
                        singleLine = true,
                    )
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
                var olderThanTypeIndex by remember {
                    mutableIntStateOf(
                        OlderThanType.entries.toTypedArray()
                            .indexOfFirst { it.name == Settings.deleteEventsOlderThan.toString() },
                    )
                }
                SettingsRow(
                    name = R.string.delete_events_older_than,
                    description = R.string.delete_events_older_than_description,
                    selectedItems = deleteItems,
                    selectedIndex = olderThanTypeIndex,
                ) {
                    olderThanTypeIndex = it
                    Settings.deleteEventsOlderThan = when (it) {
                        OlderThanType.NEVER.screenCode -> OlderThan.NEVER
                        OlderThanType.DAY.screenCode -> OlderThan.DAY
                        OlderThanType.WEEK.screenCode -> OlderThan.WEEK
                        OlderThanType.MONTH.screenCode -> OlderThan.MONTH
                        OlderThanType.YEAR.screenCode -> OlderThan.YEAR
                        else -> OlderThan.NEVER
                    }
                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                            val users = Settings.neverDeleteFrom.toMutableSet().apply { add(key) }
                            Settings.neverDeleteFrom = users
                            neverDeleteFrom = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            deleteFrom = TextFieldValue("")
                        }
                    },
                    onAdd = {
                        val key = deleteFrom.text.toNostrKey()
                        if (key == null) {
                            Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                            return@PubkeyInputRow
                        }
                        val users = Settings.neverDeleteFrom.toMutableSet().apply { add(key) }
                        Settings.neverDeleteFrom = users
                        neverDeleteFrom = users
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        deleteFrom = TextFieldValue("")
                    },
                )
            }
            items(neverDeleteFrom.toList()) { pubkey ->
                PubkeyListItem(
                    text = pubkey.toShortenHex(),
                    onDelete = {
                        val users = Settings.neverDeleteFrom.toMutableSet().apply { remove(pubkey) }
                        Settings.neverDeleteFrom = users
                        neverDeleteFrom = users
                        LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
                            Settings.autoBackup = false
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
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
            items(clients.value.keys.toList()) { item ->
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
                        webClients.update { old ->
                            val updated = old.toMutableMap().apply { remove(item) }
                            Settings.webClients = updated
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            updated
                        }
                        onApplyChanges()
                    },
                    item = item,
                )
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
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
