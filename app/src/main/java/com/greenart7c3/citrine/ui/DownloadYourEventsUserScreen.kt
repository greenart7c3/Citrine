package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.EventDownloader
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound for one relay lookup in [SelectRelayModal]. */
private const val RELAY_LOOKUP_TIMEOUT_MS = 15_000L

/**
 * Runs one relay lookup under a hard deadline.
 *
 * Timeouts and failures both degrade to "nothing found": the relay sheet must always open with
 * whatever was resolved — even nothing — so that manual relay entry and the fetch button stay
 * reachable. Nothing in here may keep the spinner up forever.
 */
private suspend fun <T> lookupRelays(
    what: String,
    block: suspend () -> T?,
): T? = try {
    withTimeoutOrNull(RELAY_LOOKUP_TIMEOUT_MS) { block() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(Citrine.TAG, "Could not load $what", e)
    null
}

/** Adds [relayUrl] to the sheet's list unless an equivalent relay is already listed. */
private fun MutableList<NormalizedRelayUrl>.addIfNew(relayUrl: String) {
    val normalized = RelayUrlNormalizer.normalizeOrNull(relayUrl) ?: return
    if (none { it.displayUrl() == normalized.displayUrl() }) add(normalized)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectRelayModal(
    signer: NostrSigner,
    onDone: (List<NormalizedRelayUrl>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        modifier = Modifier
            .fillMaxSize(),
        content = {
            val keyboardController = LocalSoftwareKeyboardController.current
            var relayText by remember { mutableStateOf(TextFieldValue()) }
            // Snapshot list: the lookups below land off the main thread, so a plain
            // MutableList would fill up without ever recomposing the list.
            val relays = remember { mutableStateListOf<NormalizedRelayUrl>() }

            var loading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                try {
                    withTimeoutOrNull(RELAY_LOOKUP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            if (!Citrine.instance.client.isActive()) {
                                Citrine.instance.client.connect()
                            }
                        }
                    }

                    // Both lookups are bounded and fall back to "nothing found": a relay that
                    // never answers, or a local query stuck behind a write, must never leave the
                    // sheet spinning with no way out. Results are added as they arrive.
                    lookupRelays("contact list for ${signer.pubKey}") {
                        withContext(Dispatchers.IO) {
                            val stored = AppDatabase.getDatabase(Citrine.instance)
                                .eventDao()
                                .getContactList(signer.pubKey)
                                ?.toEvent() as? ContactListEvent
                            stored ?: EventDownloader.fetchContactList(
                                signer = signer,
                            )
                        }
                    }?.relays()?.forEach { relays.addIfNew(it.key.url) }

                    lookupRelays("relay list for ${signer.pubKey}") {
                        withContext(Dispatchers.IO) {
                            val stored = AppDatabase.getDatabase(Citrine.instance)
                                .eventDao()
                                .getAdvertisedRelayList(signer.pubKey)
                                ?.toEvent() as? AdvertisedRelayListEvent
                            stored ?: EventDownloader.fetchAdvertisedRelayList(
                                signer = signer,
                            )
                        }
                    }?.relays()?.forEach { relays.addIfNew(it.relayUrl.url) }
                } finally {
                    loading = false
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                if (loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.loading_relays),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions.Default.copy(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (relayText.text.isBlank()) return@KeyboardActions
                            val url = RelayUrlNormalizer.normalizeOrNull(relayText.text) ?: return@KeyboardActions
                            if (relays.any { value -> value.displayUrl() == url.displayUrl() }) {
                                return@KeyboardActions
                            }

                            relays.add(url)
                            relayText = TextFieldValue()
                            keyboardController?.hide()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    value = relayText,
                    onValueChange = {
                        relayText = it
                    },
                    label = {
                        Text(stringResource(R.string.wss))
                    },
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
                        .padding(top = 4.dp),
                ) {
                    itemsIndexed(relays) { index, relay ->
                        RelayCard(
                            relay = relay.url,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                relays.removeAt(index)
                            },
                        )
                    }
                }
                ElevatedButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp),
                    content = {
                        Text(stringResource(R.string.fetch_events))
                    },
                    onClick = {
                        // TODO: find out why nostr.band keeps downloading events forever
                        onDone(relays)
                    },
                )
            }
        },
        sheetState = sheetState,
        onDismissRequest = {
            scope.launch {
                sheetState.hide()
            }
            onDismiss()
        },
    )
}

@Composable
fun DownloadYourEventsUserScreen(
    modifier: Modifier,
    navController: NavController,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noExternalSignerMsg = stringResource(R.string.no_external_signer_installed)
    var shouldShowDialog by remember { mutableStateOf(false) }
    var npub by remember { mutableStateOf(TextFieldValue()) }
    var signer by remember { mutableStateOf<NostrSigner?>(null) }
    var downloadTaggedEvents by remember { mutableStateOf(true) }

    val launcherLogin = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
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

                        val returnedKey = if (key.startsWith("npub")) {
                            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                                is NPub -> parsed.hex
                                else -> ""
                            }
                        } else {
                            key
                        }

                        val localNpub = Hex.decode(returnedKey).toNpub()

                        signer = NostrSignerExternal(
                            returnedKey,
                            packageName,
                            Citrine.instance.contentResolver,
                        )

                        npub = TextFieldValue(localNpub)
                    } catch (e: Exception) {
                        Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        },
    )

    if (shouldShowDialog) {
        SelectRelayModal(
            signer = signer!!,
            onDone = {
                shouldShowDialog = false

                Citrine.isImportingEvents = true
                Citrine.instance.cancelJob()
                Citrine.job = Citrine.instance.applicationScope.launch {
                    EventDownloader.setProgress("Connecting to ${it.size} relays")

                    RelayAuthenticator(Citrine.instance.client, Citrine.instance.applicationScope) { _, template, _ ->
                        val signer = if (signer is NostrSignerExternal) signer!! else NostrSignerInternal(KeyPair())
                        val event = signer.sign(template)
                        listOf(event)
                    }

                    EventDownloader.fetchEvents(
                        signer = signer!!,
                        relays = it,
                        downloadTaggedEvents = downloadTaggedEvents,
                    )
                }
                navController.navigateUp()
            },
            onDismiss = {
                shouldShowDialog = false
            },
        )
    }

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = npub,
            onValueChange = {
                npub = it
            },
            label = {
                Text(stringResource(R.string.npub1))
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    downloadTaggedEvents = !downloadTaggedEvents
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = downloadTaggedEvents,
                onCheckedChange = {
                    downloadTaggedEvents = it
                },
            )
            Text(stringResource(R.string.download_tagged_events))
        }
        ElevatedButton(
            content = {
                Text(stringResource(R.string.login_with_external_signer))
            },
            onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
                    val signerType = "get_public_key"
                    intent.putExtra("type", signerType)

                    val permissions =
                        listOf(
                            Permission(
                                CommandType.SIGN_EVENT,
                                22242,
                            ),
                        )
                    val jsonArray = StringBuilder("[")
                    permissions.forEachIndexed { index, permission ->
                        jsonArray.append(permission.toJson())
                        if (index < permissions.size - 1) {
                            jsonArray.append(",")
                        }
                    }
                    jsonArray.append("]")

                    intent.putExtra("permissions", jsonArray.toString())
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    launcherLogin.launch(intent)
                } catch (e: Exception) {
                    Log.d(Citrine.TAG, e.message ?: "", e)
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            noExternalSignerMsg,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
                    launcherLogin.launch(intent)
                }
            },
        )
        ElevatedButton(
            content = {
                Text(stringResource(R.string.fetch_events))
            },
            onClick = {
                if (signer == null && npub.text.isNotBlank()) {
                    val entity = Nip19Parser.uriToRoute(npub.text)

                    if (entity == null) {
                        Toast.makeText(
                            context,
                            "Invalid account",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@ElevatedButton
                    }

                    if (entity.entity !is NPub) {
                        Toast.makeText(
                            context,
                            "Invalid account",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@ElevatedButton
                    }

                    signer = NostrSignerInternal(
                        KeyPair(
                            pubKey = Hex.decode((entity.entity as NPub).hex),
                        ),
                    )
                }

                if (signer == null) {
                    Toast.makeText(
                        context,
                        "Invalid account",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@ElevatedButton
                }

                shouldShowDialog = true
            },
        )
    }
}

@Composable
fun RelayCard(
    modifier: Modifier = Modifier,
    relay: String,
    onClick: () -> Unit,
) {
    Card(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        border = BorderStroke(1.dp, Color.LightGray),
        colors = CardDefaults.cardColors().copy(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                relay,
                Modifier
                    .weight(0.9f)
                    .padding(8.dp)
                    .padding(start = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onClick,
            ) {
                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.delete),
                )
            }
        }
    }
}
