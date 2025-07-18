@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui

import android.net.InetAddresses.isNumericAddress
import android.os.Build
import android.util.Patterns
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.SettingsRow
import com.greenart7c3.citrine.ui.components.TitleExplainer
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
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        modifier,
    ) {
        val clipboardManager = LocalClipboard.current
        val context = LocalContext.current
        var host by remember {
            mutableStateOf(TextFieldValue(Settings.host))
        }
        var port by remember {
            mutableStateOf(TextFieldValue(Settings.port.toString()))
        }
        var deleteExpiredEvents by remember {
            mutableStateOf(Settings.deleteExpiredEvents)
        }
        var listenToPokeyBroadcasts by remember {
            mutableStateOf(Settings.listenToPokeyBroadcasts)
        }
        var useAuth by remember {
            mutableStateOf(Settings.authEnabled)
        }
        var deleteEphemeralEvents by remember {
            mutableStateOf(Settings.deleteEphemeralEvents)
        }
        var relayName by remember {
            mutableStateOf(TextFieldValue(Settings.name))
        }
        var relayOwnerPubkey by remember {
            mutableStateOf(TextFieldValue(Settings.ownerPubkey))
        }
        var relayContact by remember {
            mutableStateOf(TextFieldValue(Settings.contact))
        }
        var relayDescription by remember {
            mutableStateOf(TextFieldValue(Settings.description))
        }
        var relayIconUrl by remember {
            mutableStateOf(TextFieldValue(Settings.relayIcon))
        }
//        var useSSL by remember {
//            mutableStateOf(Settings.useSSL)
//        }
        var signedBy by remember {
            mutableStateOf(TextFieldValue(""))
        }
        var referredBy by remember {
            mutableStateOf(TextFieldValue(""))
        }
        var kind by remember {
            mutableStateOf(TextFieldValue(""))
        }
        var deleteFrom by remember {
            mutableStateOf(TextFieldValue(""))
        }
        var allowedPubKeys by remember {
            mutableStateOf(Settings.allowedPubKeys)
        }
        var allowedTaggedPubKeys by remember {
            mutableStateOf(Settings.allowedTaggedPubKeys)
        }
        var allowedKinds by remember {
            mutableStateOf(Settings.allowedKinds)
        }
        var neverDeleteFrom by remember {
            mutableStateOf(Settings.neverDeleteFrom)
        }
        var startOnBoot by remember {
            mutableStateOf(Settings.startOnBoot)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Server",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = host,
                    label = {
                        Text(text = "Host")
                    },
                    onValueChange = {
                        host = it
                    },
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = port,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    label = {
                        Text(text = "Port")
                    },
                    onValueChange = {
                        port = it
                    },
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = relayName,
                    label = {
                        Text(text = "Relay Name")
                    },
                    onValueChange = {
                        relayName = it
                    },
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = relayOwnerPubkey,
                    label = {
                        Text(text = "Relay Owner Pubkey")
                    },
                    onValueChange = {
                        relayOwnerPubkey = it
                    },
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = relayContact,
                    label = {
                        Text(text = "Relay Contact")
                    },
                    onValueChange = {
                        relayContact = it
                    },
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = relayDescription,
                    label = {
                        Text(text = "Relay Description")
                    },
                    onValueChange = {
                        relayDescription = it
                    },
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    value = relayIconUrl,
                    label = {
                        Text(text = "Relay Icon URL")
                    },
                    onValueChange = {
                        relayIconUrl = it
                    },
                )
            }
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    Arrangement.Center,
                    Alignment.CenterHorizontally,
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
                                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                onApplyChanges()
                                delay(1500)
                                isLoading = false
                            }
                        },
                        content = {
                            Text("Default")
                        },
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    ElevatedButton(
                        enabled = !isLoading,
                        onClick = {
                            if (host.text.isNotBlank() && isIpValid(host.text) && port.text.isDigitsOnly() && port.text.isNotBlank()) {
                                if (relayOwnerPubkey.text.isNotBlank() && relayOwnerPubkey.text.toNostrKey() == null) {
                                    Toast.makeText(
                                        context,
                                        "Invalid owner pubkey",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    return@ElevatedButton
                                }

                                if (relayIconUrl.text.isNotBlank()) {
                                    try {
                                        relayIconUrl.text.toUri()
                                    } catch (_: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Invalid icon URL",
                                            Toast.LENGTH_SHORT,
                                        ).show()
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
                            } else {
                                Toast.makeText(
                                    context,
                                    "Invalid host or port",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        content = {
                            Text("Apply changes")
                        },
                    )
                }
            }
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Accept events signed by",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        value = signedBy,
                        onValueChange = {
                            signedBy = it
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.let {
                                            signedBy = TextFieldValue(it.text.toString())

                                            val key = signedBy.text.toNostrKey()

                                            if (key == null) {
                                                Toast.makeText(
                                                    context,
                                                    "Invalid key",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                return@launch
                                            }

                                            val users = Settings.allowedPubKeys.toMutableSet()
                                            users.add(key)
                                            Settings.allowedPubKeys = users
                                            allowedPubKeys = Settings.allowedPubKeys
                                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                            signedBy = TextFieldValue("")
                                        }
                                    }
                                    signedBy = TextFieldValue("")
                                },
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                )
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            val key = signedBy.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(
                                    context,
                                    "Invalid key",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@IconButton
                            }

                            val users = Settings.allowedPubKeys.toMutableSet()
                            users.add(key)
                            Settings.allowedPubKeys = users
                            allowedPubKeys = Settings.allowedPubKeys
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            signedBy = TextFieldValue("")
                        },
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                        )
                    }
                }
            }
            items(allowedPubKeys.size) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        allowedPubKeys.elementAt(index).toShortenHex(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.9f),
                    )
                    IconButton(
                        onClick = {
                            val users = Settings.allowedPubKeys.toMutableSet()
                            users.remove(allowedPubKeys.elementAt(index))
                            Settings.allowedPubKeys = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            allowedPubKeys = Settings.allowedPubKeys
                        },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                        )
                    }
                }
            }
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Accept events that refer to",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        value = referredBy,
                        onValueChange = {
                            referredBy = it
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.let {
                                            referredBy = TextFieldValue(it.text.toString())

                                            val key = referredBy.text.toNostrKey()
                                            if (key == null) {
                                                Toast.makeText(
                                                    context,
                                                    "Invalid key",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                return@launch
                                            }

                                            val users = Settings.allowedTaggedPubKeys.toMutableSet()
                                            users.add(key)
                                            Settings.allowedTaggedPubKeys = users
                                            allowedTaggedPubKeys = Settings.allowedTaggedPubKeys
                                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                            referredBy = TextFieldValue("")
                                        }
                                    }
                                    referredBy = TextFieldValue("")
                                },
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                )
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            val key = referredBy.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(
                                    context,
                                    "Invalid key",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@IconButton
                            }

                            val users = Settings.allowedTaggedPubKeys.toMutableSet()
                            users.add(key)
                            Settings.allowedTaggedPubKeys = users
                            allowedTaggedPubKeys = Settings.allowedTaggedPubKeys
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            referredBy = TextFieldValue("")
                        },
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                        )
                    }
                }
            }
            items(allowedTaggedPubKeys.size) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        allowedTaggedPubKeys.elementAt(index).toShortenHex(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.9f),
                    )
                    IconButton(
                        onClick = {
                            val users = Settings.allowedTaggedPubKeys.toMutableSet()
                            users.remove(allowedTaggedPubKeys.elementAt(index))
                            Settings.allowedTaggedPubKeys = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            allowedTaggedPubKeys = Settings.allowedTaggedPubKeys
                        },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                        )
                    }
                }
            }
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Allowed kinds",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        value = kind,
                        onValueChange = {
                            kind = it
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.let {
                                            kind = TextFieldValue(it.text.toString())

                                            if (kind.text.toIntOrNull() == null) {
                                                Toast.makeText(
                                                    context,
                                                    "Invalid kind",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                return@launch
                                            }

                                            val users = Settings.allowedKinds.toMutableSet()
                                            users.add(kind.text.toInt())
                                            Settings.allowedKinds = users
                                            allowedKinds = Settings.allowedKinds
                                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                            kind = TextFieldValue("")
                                        }
                                    }
                                    kind = TextFieldValue("")
                                },
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                )
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            if (kind.text.toIntOrNull() == null) {
                                Toast.makeText(
                                    context,
                                    "Invalid kind",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@IconButton
                            }

                            val users = Settings.allowedKinds.toMutableSet()
                            users.add(kind.text.toInt())
                            Settings.allowedKinds = users
                            allowedKinds = Settings.allowedKinds
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            kind = TextFieldValue("")
                        },
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                        )
                    }
                }
            }
            items(allowedKinds.size) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        allowedKinds.elementAt(index).toString(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.9f),
                    )
                    IconButton(
                        onClick = {
                            val users = Settings.allowedKinds.toMutableSet()
                            users.remove(allowedKinds.elementAt(index))
                            Settings.allowedKinds = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            allowedKinds = Settings.allowedKinds
                        },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                        )
                    }
                }
            }
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Others",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            startOnBoot = !startOnBoot
                            Settings.startOnBoot = !Settings.startOnBoot
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.start_on_boot),
                    )
                    Switch(
                        checked = startOnBoot,
                        onCheckedChange = {
                            startOnBoot = !startOnBoot
                            Settings.startOnBoot = !Settings.startOnBoot
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            useAuth = !useAuth
                            Settings.authEnabled = !Settings.authEnabled
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.enable_disable_auth),
                    )
                    Switch(
                        checked = useAuth,
                        onCheckedChange = {
                            useAuth = !useAuth
                            Settings.authEnabled = !Settings.authEnabled
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            listenToPokeyBroadcasts = !listenToPokeyBroadcasts
                            Settings.listenToPokeyBroadcasts = !Settings.listenToPokeyBroadcasts
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.listen_to_pokey_broadcasts),
                    )
                    Switch(
                        checked = listenToPokeyBroadcasts,
                        onCheckedChange = {
                            listenToPokeyBroadcasts = !listenToPokeyBroadcasts
                            Settings.listenToPokeyBroadcasts = !Settings.listenToPokeyBroadcasts
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            if (listenToPokeyBroadcasts) {
                                Citrine.getInstance().registerPokeyReceiver()
                            } else {
                                Citrine.getInstance().unregisterPokeyReceiver()
                            }
                        },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deleteExpiredEvents = !deleteExpiredEvents
                            Settings.deleteExpiredEvents = !Settings.deleteExpiredEvents
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.delete_expired_events),
                    )
                    Switch(
                        checked = deleteExpiredEvents,
                        onCheckedChange = {
                            deleteExpiredEvents = it
                            Settings.deleteExpiredEvents = it
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            deleteEphemeralEvents = !deleteEphemeralEvents
                            Settings.deleteEphemeralEvents = !Settings.deleteEphemeralEvents
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.delete_ephemeral_events),
                    )
                    Switch(
                        checked = deleteEphemeralEvents,
                        onCheckedChange = {
                            deleteEphemeralEvents = it
                            Settings.deleteEphemeralEvents = it
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                        },
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
                    mutableIntStateOf(OlderThanType.entries.toTypedArray().indexOfFirst { it.name == Settings.deleteEventsOlderThan.toString() })
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
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Never delete from",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        value = deleteFrom,
                        onValueChange = {
                            deleteFrom = it
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.let {
                                            deleteFrom = TextFieldValue(it.text.toString())

                                            val key = deleteFrom.text.toNostrKey()

                                            if (key == null) {
                                                Toast.makeText(
                                                    context,
                                                    "Invalid key",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                return@launch
                                            }

                                            val users = Settings.neverDeleteFrom.toMutableSet()
                                            users.add(key)
                                            Settings.neverDeleteFrom = users
                                            neverDeleteFrom = Settings.neverDeleteFrom
                                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                            deleteFrom = TextFieldValue("")
                                        }
                                    }
                                    deleteFrom = TextFieldValue("")
                                },
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste from clipboard",
                                )
                            }
                        },
                    )
                    IconButton(
                        onClick = {
                            val key = deleteFrom.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(
                                    context,
                                    "Invalid key",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@IconButton
                            }

                            val users = Settings.neverDeleteFrom.toMutableSet()
                            users.add(key)
                            Settings.neverDeleteFrom = users
                            neverDeleteFrom = Settings.neverDeleteFrom
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            deleteFrom = TextFieldValue("")
                        },
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                        )
                    }
                }
            }
            items(neverDeleteFrom.size) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        neverDeleteFrom.elementAt(index).toShortenHex(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.9f),
                    )
                    IconButton(
                        onClick = {
                            val users = Settings.neverDeleteFrom.toMutableSet()
                            users.remove(neverDeleteFrom.elementAt(index))
                            Settings.neverDeleteFrom = users
                            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                            neverDeleteFrom = Settings.neverDeleteFrom
                        },
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                        )
                    }
                }
            }
        }
    }
}

fun String.toShortenHex(): String {
    if (length <= 16) return this
    return replaceRange(8, length - 8, ":")
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
