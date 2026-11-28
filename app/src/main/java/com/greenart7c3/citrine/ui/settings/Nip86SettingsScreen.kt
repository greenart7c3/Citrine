@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem

@Composable
fun Nip86SettingsScreen(
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: Nip86SettingsViewModel = viewModel(factory = Nip86SettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val context = LocalContext.current
    val paste = rememberClipboardPaste()
    val invalidKeyMsg = stringResource(R.string.invalid_key)
    val invalidEventIdMsg = stringResource(R.string.invalid_event_id)
    val invalidIpMsg = stringResource(R.string.invalid_ip)

    Surface(modifier) {
        var purgeDialogPubkey by remember { mutableStateOf<String?>(null) }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                nip86StatusItem()
                banListItems(
                    bannedPubKeys = viewModel.bannedPubKeys,
                    bannedEventIds = viewModel.bannedEventIds,
                    blockedIps = viewModel.blockedIps,
                    paste = paste,
                    onBannedPubkey = { key ->
                        if (key == null) {
                            Toast.makeText(context, invalidKeyMsg, Toast.LENGTH_SHORT).show()
                        } else {
                            purgeDialogPubkey = key
                        }
                    },
                    onInvalidEventId = { Toast.makeText(context, invalidEventIdMsg, Toast.LENGTH_SHORT).show() },
                    onInvalidIp = { Toast.makeText(context, invalidIpMsg, Toast.LENGTH_SHORT).show() },
                )
            }

            SettingsApplyBar(enabled = !viewModel.isLoading, onApply = { viewModel.applyChanges(onApplyChanges) })
        }

        purgeDialogPubkey?.let { pubkey ->
            PurgeEventsDialog(
                pubkey = pubkey,
                onConfirm = {
                    viewModel.purgeOnApply(pubkey)
                    purgeDialogPubkey = null
                },
                onDismiss = { purgeDialogPubkey = null },
            )
        }
    }
}

private fun LazyListScope.nip86StatusItem() {
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
}

private fun LazyListScope.banListItems(
    bannedPubKeys: EditableBanList,
    bannedEventIds: EditableBanList,
    blockedIps: EditableBanList,
    paste: (onText: (String) -> Unit) -> Unit,
    onBannedPubkey: (String?) -> Unit,
    onInvalidEventId: () -> Unit,
    onInvalidIp: () -> Unit,
) {
    banSectionItems(
        header = R.string.banned_pubkeys,
        emptyHint = R.string.no_banned_pubkeys_hint,
        list = bannedPubKeys,
        paste = paste,
        onAdded = onBannedPubkey,
    )
    banSectionItems(
        header = R.string.banned_events,
        emptyHint = R.string.no_banned_events_hint,
        list = bannedEventIds,
        paste = paste,
        onAdded = { if (it == null) onInvalidEventId() },
    )
    banSectionItems(
        header = R.string.blocked_ips,
        emptyHint = R.string.no_blocked_ips_hint,
        list = blockedIps,
        paste = paste,
        onAdded = { if (it == null) onInvalidIp() },
        displayKey = { it },
    )
}

private fun LazyListScope.banSectionItems(
    @StringRes header: Int,
    @StringRes emptyHint: Int,
    list: EditableBanList,
    paste: (onText: (String) -> Unit) -> Unit,
    onAdded: (String?) -> Unit,
    displayKey: (String) -> String = { it.toShortenHex() },
) {
    stickyHeader {
        SectionHeader(stringResource(header))
    }
    item {
        PubkeyInputRow(
            value = list.input,
            onValueChange = { list.input = it },
            onPaste = { paste { onAdded(list.paste(it)) } },
            onAdd = { onAdded(list.add()) },
        )
    }
    if (list.entries.isEmpty()) {
        item {
            EmptyListHint(stringResource(emptyHint))
        }
    }
    items(list.entries.toList()) { (key, reason) ->
        PubkeyListItem(
            text = if (reason.isBlank()) displayKey(key) else "${displayKey(key)} — $reason",
            onDelete = { list.remove(key) },
        )
    }
}

@Composable
private fun PurgeEventsDialog(
    pubkey: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_banned_pubkey_events_title)) },
        text = { Text(stringResource(R.string.delete_banned_pubkey_events_message, pubkey.toShortenHex())) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.no))
            }
        },
    )
}
