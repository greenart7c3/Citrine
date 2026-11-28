package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.Context
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.EventDownloader
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    viewModel: DownloadYourEventsViewModel = viewModel(factory = DownloadYourEventsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val scope = rememberCoroutineScope()
    val relayClient = LocalRelayClient.current

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
                            if (!relayClient.client.isActive()) {
                                relayClient.client.connect()
                            }
                        }
                    }

                    // Both lookups are bounded and fall back to "nothing found": a relay that
                    // never answers, or a local query stuck behind a write, must never leave the
                    // sheet spinning with no way out. Results are added as they arrive.
                    lookupRelays("contact list for ${signer.pubKey}") {
                        withContext(Dispatchers.IO) { viewModel.contactList(signer) }
                    }?.relays()?.forEach { relays.addIfNew(it.key.url) }

                    lookupRelays("relay list for ${signer.pubKey}") {
                        withContext(Dispatchers.IO) { viewModel.relayList(signer) }
                    }?.relays()?.forEach { relays.addIfNew(it.relayUrl.url) }
                } finally {
                    loading = false
                }
            }

            RelaySheetBody(
                loading = loading,
                relayText = relayText,
                onRelayTextChange = { relayText = it },
                onAddTypedRelay = {
                    if (relays.addTyped(relayText.text)) {
                        relayText = TextFieldValue()
                        keyboardController?.hide()
                    }
                },
                relays = relays,
                onRemoveRelay = { index -> relays.removeAt(index) },
                onFetch = {
                    // TODO: find out why nostr.band keeps downloading events forever
                    onDone(relays)
                },
            )
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

private fun MutableList<NormalizedRelayUrl>.addTyped(relayText: String): Boolean {
    if (relayText.isBlank()) return false
    val url = RelayUrlNormalizer.normalizeOrNull(relayText) ?: return false
    if (any { value -> value.displayUrl() == url.displayUrl() }) {
        return false
    }

    add(url)
    return true
}

@Composable
private fun RelaySheetBody(
    loading: Boolean,
    relayText: TextFieldValue,
    onRelayTextChange: (TextFieldValue) -> Unit,
    onAddTypedRelay: () -> Unit,
    relays: List<NormalizedRelayUrl>,
    onRemoveRelay: (Int) -> Unit,
    onFetch: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (loading) {
            RelayLookupProgress()
        }
        OutlinedTextField(
            keyboardOptions = KeyboardOptions.Default.copy(
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.None,
            ),
            keyboardActions = KeyboardActions(
                onDone = { onAddTypedRelay() },
            ),
            modifier = Modifier.fillMaxWidth(),
            value = relayText,
            onValueChange = onRelayTextChange,
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
                        onRemoveRelay(index)
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
            onClick = onFetch,
        )
    }
}

@Composable
private fun RelayLookupProgress() {
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

@Composable
fun DownloadYourEventsUserScreen(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val relayClient = LocalRelayClient.current
    val appScope = LocalAppScope.current
    var shouldShowDialog by remember { mutableStateOf(false) }
    var npub by remember { mutableStateOf(TextFieldValue()) }
    var signer by remember { mutableStateOf<NostrSigner?>(null) }
    var downloadTaggedEvents by remember { mutableStateOf(true) }

    val loginWithExternalSigner = rememberDownloadLogin { login ->
        signer = login.signer
        npub = TextFieldValue(login.npub)
    }

    if (shouldShowDialog) {
        SelectRelayModal(
            signer = signer!!,
            onDone = {
                shouldShowDialog = false
                startDownload(
                    signer = signer!!,
                    relays = it,
                    downloadTaggedEvents = downloadTaggedEvents,
                    relayClient = relayClient,
                    appScope = appScope,
                )
                navController.navigateUp()
            },
            onDismiss = {
                shouldShowDialog = false
            },
        )
    }

    DownloadForm(
        npub = npub,
        onNpubChange = { npub = it },
        downloadTaggedEvents = downloadTaggedEvents,
        onDownloadTaggedEventsChange = { downloadTaggedEvents = it },
        onLogin = loginWithExternalSigner,
        onFetch = {
            if (signer == null && npub.text.isNotBlank()) {
                signer = npubSigner(npub.text)
            }

            if (signer == null) {
                Toast.makeText(
                    context,
                    "Invalid account",
                    Toast.LENGTH_SHORT,
                ).show()
                return@DownloadForm
            }

            shouldShowDialog = true
        },
        modifier = modifier,
    )
}

@Composable
private fun rememberDownloadLogin(onLogin: (LoginResult) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noExternalSignerMsg = stringResource(R.string.no_external_signer_installed)

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
                    loginResult(it, appContext)?.let(onLogin)
                }
            }
        },
    )

    return {
        try {
            launcherLogin.launch(downloadLoginIntent())
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
    }
}

private class LoginResult(val signer: NostrSigner, val npub: String)

private fun loginResult(data: Intent, appContext: Context): LoginResult? = try {
    val key = data.getStringExtra("signature") ?: ""
    val packageName = data.getStringExtra("package") ?: ""

    val returnedKey = if (key.startsWith("npub")) {
        when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
            is NPub -> parsed.hex
            else -> ""
        }
    } else {
        key
    }

    val localNpub = Hex.decode(returnedKey).toNpub()

    LoginResult(
        signer = NostrSignerExternal(
            returnedKey,
            packageName,
            appContext.contentResolver,
        ),
        npub = localNpub,
    )
} catch (e: Exception) {
    Log.d(Citrine.TAG, e.message ?: "", e)
    null
}

private fun downloadLoginIntent(): Intent {
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
    return intent
}

private fun npubSigner(text: String): NostrSigner? {
    val entity = Nip19Parser.uriToRoute(text)?.entity as? NPub ?: return null
    return NostrSignerInternal(
        KeyPair(
            pubKey = Hex.decode(entity.hex),
        ),
    )
}

private fun startDownload(
    signer: NostrSigner,
    relays: List<NormalizedRelayUrl>,
    downloadTaggedEvents: Boolean,
    relayClient: RelayClient,
    appScope: CoroutineScope,
) {
    Citrine.isImportingEvents = true
    Citrine.cancelJob()
    Citrine.job = appScope.launch {
        EventDownloader.setProgress("Connecting to ${relays.size} relays")

        RelayAuthenticator(relayClient.client, appScope) { _, template, _ ->
            val authSigner = if (signer is NostrSignerExternal) signer else NostrSignerInternal(KeyPair())
            val event = authSigner.sign(template)
            listOf(event)
        }

        EventDownloader.fetchEvents(
            signer = signer,
            relays = relays,
            downloadTaggedEvents = downloadTaggedEvents,
        )
    }
}

@Composable
private fun DownloadForm(
    npub: TextFieldValue,
    onNpubChange: (TextFieldValue) -> Unit,
    downloadTaggedEvents: Boolean,
    onDownloadTaggedEventsChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = npub,
            onValueChange = onNpubChange,
            label = {
                Text(stringResource(R.string.npub1))
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onDownloadTaggedEventsChange(!downloadTaggedEvents)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = downloadTaggedEvents,
                onCheckedChange = onDownloadTaggedEventsChange,
            )
            Text(stringResource(R.string.download_tagged_events))
        }
        ElevatedButton(
            content = {
                Text(stringResource(R.string.login_with_external_signer))
            },
            onClick = onLogin,
        )
        ElevatedButton(
            content = {
                Text(stringResource(R.string.fetch_events))
            },
            onClick = onFetch,
        )
    }
}

@Composable
fun RelayCard(
    relay: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
