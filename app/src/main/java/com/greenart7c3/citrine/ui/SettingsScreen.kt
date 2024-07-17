@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.ui.components.SettingsRow
import com.greenart7c3.citrine.ui.components.TitleExplainer
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
        var useSSL by remember {
            mutableStateOf(Settings.useSSL)
        }
        var signedBy by remember {
            mutableStateOf(TextFieldValue(""))
        }
        var allowedPubKeys by remember {
            mutableStateOf(Settings.allowedPubKeys)
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
                        Settings.host = it.text
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
                        if (it.text.isDigitsOnly() && it.text.isNotBlank()) {
                            Settings.port = it.text.toInt()
                        }
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            useSSL = !useSSL
                            Settings.useSSL = !Settings.useSSL
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = "Use SSL",
                    )
                    Switch(
                        checked = useSSL,
                        onCheckedChange = {
                            useSSL = !useSSL
                            Settings.useSSL = !Settings.useSSL
                        },
                    )
                }
            }
            item {
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    ElevatedButton(
                        onClick = onApplyChanges,
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
            if (allowedPubKeys.isEmpty()) {
                item {
                    Text("Events signed by anyone are allowed")
                }
            }
            items(allowedPubKeys.size) { index ->
                Text(allowedPubKeys.elementAt(index))
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
                            val users = Settings.allowedPubKeys.toMutableSet()
                            users.add(signedBy.text)
                            Settings.allowedPubKeys = users
                            allowedPubKeys = Settings.allowedPubKeys
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
            if (Settings.allowedTaggedPubKeys.isEmpty()) {
                item {
                    Text("Events referring to anyone are allowed")
                }
            }
            items(Settings.allowedTaggedPubKeys.size) { index ->
                Text(Settings.allowedTaggedPubKeys.elementAt(index))
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
            if (Settings.allowedKinds.isEmpty()) {
                item {
                    Text("All kinds are allowed")
                }
            }
            items(Settings.allowedKinds.size) { index ->
                Text(Settings.allowedKinds.elementAt(index).toString())
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
                    mutableIntStateOf(OlderThanType.NEVER.screenCode)
                }
                SettingsRow(
                    name = R.string.delete_events_older_than,
                    description = R.string.delete_events_older_than_description,
                    selectedItems = deleteItems,
                    selectedIndex = olderThanTypeIndex,
                ) {
                    olderThanTypeIndex = it
                }
            }
        }
    }
}
