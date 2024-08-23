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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.SettingsRow
import com.greenart7c3.citrine.ui.components.TitleExplainer
import com.vitorpamplona.quartz.encoders.Hex
import com.vitorpamplona.quartz.encoders.Nip19Bech32
import com.vitorpamplona.quartz.encoders.hexToByteArray
import com.vitorpamplona.quartz.encoders.toHexKey
import kotlinx.collections.immutable.persistentListOf

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    Surface(
        modifier,
    ) {
        val clipboardManager = LocalClipboardManager.current
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
        var deleteEphemeralEvents by remember {
            mutableStateOf(Settings.deleteEphemeralEvents)
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        ) {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Server",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { /*TODO*/ }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
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
//            item {
//                Row(
//                    modifier = Modifier
//                        .fillMaxWidth()
//                        .clickable {
//                            useSSL = !useSSL
//                            Settings.useSSL = !Settings.useSSL
//                        },
//                    horizontalArrangement = Arrangement.SpaceBetween,
//                    verticalAlignment = Alignment.CenterVertically,
//                ) {
//                    Text(
//                        modifier = Modifier.weight(1f),
//                        text = "Use SSL",
//                    )
//                    Switch(
//                        checked = useSSL,
//                        onCheckedChange = {
//                            useSSL = !useSSL
//                            Settings.useSSL = !Settings.useSSL
//                        },
//                    )
//                }
//            }
            item {
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ElevatedButton(
                        onClick = {
                            if (host.text.isNotBlank() && isIpValid(host.text) && port.text.isDigitsOnly() && port.text.isNotBlank()) {
                                Settings.port = port.text.toInt()
                                Settings.host = host.text
                                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                                onApplyChanges()
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
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Accept events signed by",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { /*TODO*/ }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
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
                                    clipboardManager.getText()?.let {
                                        signedBy = TextFieldValue(it)

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
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Accept events that refer to",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { /*TODO*/ }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
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
                                    clipboardManager.getText()?.let {
                                        referredBy = TextFieldValue(it)

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
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Allowed kinds",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { /*TODO*/ }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
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
                                    clipboardManager.getText()?.let {
                                        kind = TextFieldValue(it)

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
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Others",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { /*TODO*/ }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
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
                        text = "Delete expired events",
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
                        text = "Delete ephemeral events",
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
                        .background(MaterialTheme.colorScheme.surface),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Never delete from",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    IconButton(onClick = { /*TODO*/ }) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                        )
                    }
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
                                    clipboardManager.getText()?.let {
                                        deleteFrom = TextFieldValue(it)

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

fun isIpValid(ip: String): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isNumericAddress(ip)
    } else {
        @Suppress("DEPRECATION")
        Patterns.IP_ADDRESS.matcher(ip).matches()
    }
}

fun String.toNostrKey(): String? {
    val key = try {
        Hex.decode(this)
    } catch (e: Exception) {
        null
    }
    if (key != null) {
        return this
    }

    val pubKeyParsed =
        when (val parsed = Nip19Bech32.uriToRoute(this)?.entity) {
            is Nip19Bech32.NSec -> null
            is Nip19Bech32.NPub -> parsed.hex.hexToByteArray()
            is Nip19Bech32.NProfile -> parsed.hex.hexToByteArray()
            is Nip19Bech32.Note -> null
            is Nip19Bech32.NEvent -> null
            is Nip19Bech32.NEmbed -> null
            is Nip19Bech32.NRelay -> null
            is Nip19Bech32.NAddress -> null
            else -> null
        }
    return pubKeyParsed?.toHexKey()
}