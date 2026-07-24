@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val EVENT_ID_REGEX = Regex("^[0-9a-f]{64}$")

@Composable
fun Nip86SettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val invalidKeyMsg = stringResource(R.string.invalid_key)
    val invalidEventIdMsg = stringResource(R.string.invalid_event_id)
    val invalidIpMsg = stringResource(R.string.invalid_ip)

    Surface(modifier) {
        var bannedPubKeys by remember { mutableStateOf(Settings.bannedPubKeys.toMap()) }
        var bannedEventIds by remember { mutableStateOf(Settings.bannedEventIds.toMap()) }
        var blockedIps by remember { mutableStateOf(Settings.blockedIps.toMap()) }

        var bannedPubkeyInput by remember { mutableStateOf(TextFieldValue("")) }
        var bannedEventInput by remember { mutableStateOf(TextFieldValue("")) }
        var blockedIpInput by remember { mutableStateOf(TextFieldValue("")) }

        // Pubkeys the user opted to purge from the database. Deletion runs on
        // Apply, so backing out of the screen without applying deletes nothing.
        var pubkeysToPurge by remember { mutableStateOf(setOf<String>()) }
        var purgeDialogPubkey by remember { mutableStateOf<String?>(null) }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.bannedPubKeys = java.util.concurrent.ConcurrentHashMap(bannedPubKeys)
                Settings.bannedEventIds = java.util.concurrent.ConcurrentHashMap(bannedEventIds)
                Settings.blockedIps = java.util.concurrent.ConcurrentHashMap(blockedIps)
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                val eventDao = AppDatabase.getDatabase(context).eventDao()
                pubkeysToPurge.intersect(bannedPubKeys.keys).forEach { pubkey ->
                    eventDao.deleteByPubkey(pubkey)
                }
                pubkeysToPurge = emptySet()
                onApplyChanges()
                delay(1500)
                isLoading = false
            }
            Unit
        }

        val addBannedPubkey: (String) -> Boolean = { text ->
            val key = text.toNostrKey()
            if (key == null) {
                Toast.makeText(context, invalidKeyMsg, Toast.LENGTH_SHORT).show()
                false
            } else {
                bannedPubKeys = bannedPubKeys + (key to "")
                purgeDialogPubkey = key
                true
            }
        }
        val addBannedEvent: (String) -> Boolean = { text ->
            val id = text.trim().lowercase()
            if (!EVENT_ID_REGEX.matches(id)) {
                Toast.makeText(context, invalidEventIdMsg, Toast.LENGTH_SHORT).show()
                false
            } else {
                bannedEventIds = bannedEventIds + (id to "")
                true
            }
        }
        val addBlockedIp: (String) -> Boolean = { text ->
            val ip = text.trim()
            if (!isIpValid(ip)) {
                Toast.makeText(context, invalidIpMsg, Toast.LENGTH_SHORT).show()
                false
            } else {
                blockedIps = blockedIps + (ip to "")
                true
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (Settings.ownerPubkey.isNotBlank()) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    ) {
                        Text(
                            text = stringResource(
                                if (Settings.ownerPubkey.isNotBlank()) {
                                    R.string.nip86_status_enabled
                                } else {
                                    R.string.nip86_status_disabled
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.banned_pubkeys))
                }
                item {
                    PubkeyInputRow(
                        value = bannedPubkeyInput,
                        onValueChange = { bannedPubkeyInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                bannedPubkeyInput = TextFieldValue(text)
                                if (addBannedPubkey(text)) bannedPubkeyInput = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            if (addBannedPubkey(bannedPubkeyInput.text)) bannedPubkeyInput = TextFieldValue("")
                        },
                    )
                }
                if (bannedPubKeys.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.no_banned_pubkeys_hint))
                    }
                }
                items(bannedPubKeys.toList()) { (pubkey, reason) ->
                    PubkeyListItem(
                        text = if (reason.isBlank()) pubkey.toShortenHex() else "${pubkey.toShortenHex()} — $reason",
                        onDelete = { bannedPubKeys = bannedPubKeys - pubkey },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.banned_events))
                }
                item {
                    PubkeyInputRow(
                        value = bannedEventInput,
                        onValueChange = { bannedEventInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                bannedEventInput = TextFieldValue(text)
                                if (addBannedEvent(text)) bannedEventInput = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            if (addBannedEvent(bannedEventInput.text)) bannedEventInput = TextFieldValue("")
                        },
                    )
                }
                if (bannedEventIds.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.no_banned_events_hint))
                    }
                }
                items(bannedEventIds.toList()) { (id, reason) ->
                    PubkeyListItem(
                        text = if (reason.isBlank()) id.toShortenHex() else "${id.toShortenHex()} — $reason",
                        onDelete = { bannedEventIds = bannedEventIds - id },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.blocked_ips))
                }
                item {
                    PubkeyInputRow(
                        value = blockedIpInput,
                        onValueChange = { blockedIpInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                blockedIpInput = TextFieldValue(text)
                                if (addBlockedIp(text)) blockedIpInput = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            if (addBlockedIp(blockedIpInput.text)) blockedIpInput = TextFieldValue("")
                        },
                    )
                }
                if (blockedIps.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.no_blocked_ips_hint))
                    }
                }
                items(blockedIps.toList()) { (ip, reason) ->
                    PubkeyListItem(
                        text = if (reason.isBlank()) ip else "$ip — $reason",
                        onDelete = { blockedIps = blockedIps - ip },
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }

        purgeDialogPubkey?.let { pubkey ->
            AlertDialog(
                onDismissRequest = { purgeDialogPubkey = null },
                title = { Text(stringResource(R.string.delete_banned_pubkey_events_title)) },
                text = { Text(stringResource(R.string.delete_banned_pubkey_events_message, pubkey.toShortenHex())) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pubkeysToPurge = pubkeysToPurge + pubkey
                            purgeDialogPubkey = null
                        },
                    ) {
                        Text(stringResource(R.string.yes))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { purgeDialogPubkey = null }) {
                        Text(stringResource(R.string.no))
                    }
                },
            )
        }
    }
}
