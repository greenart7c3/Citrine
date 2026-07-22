package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.EventDownloader
import com.greenart7c3.citrine.service.EventRebroadcaster
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.Hex
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RebroadcastRequest(
    val relays: List<NormalizedRelayUrl>,
    val authorPubKey: String?,
    val kinds: Set<Int>,
    val since: Long?,
    val until: Long?,
    val includePrivateKinds: Boolean,
)

@Composable
fun RebroadcastScreen(
    modifier: Modifier,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val state by EventRebroadcaster.state.collectAsStateWithLifecycle()

    var account by remember { mutableStateOf(TextFieldValue()) }
    var kindsText by remember { mutableStateOf(TextFieldValue()) }
    var fromDateText by remember { mutableStateOf(TextFieldValue()) }
    var toDateText by remember { mutableStateOf(TextFieldValue()) }
    var includePrivateKinds by remember { mutableStateOf(false) }
    var relayText by remember { mutableStateOf(TextFieldValue()) }
    val relays = remember { listOf<NormalizedRelayUrl>().toMutableStateList() }
    var pendingRequest by remember { mutableStateOf<RebroadcastRequest?>(null) }
    var loadingUserRelays by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun addRelays(found: List<NormalizedRelayUrl>) {
        found.forEach { url ->
            if (!Citrine.instance.isPrivateIp(url.url) && relays.none { it.displayUrl() == url.displayUrl() }) {
                relays.add(url)
            }
        }
    }

    fun addRelay() {
        if (relayText.text.isBlank()) return
        val url = RelayUrlNormalizer.normalizeOrNull(relayText.text)
        if (url == null) {
            toast(context.getString(R.string.relay_aggregator_invalid_relay))
            return
        }
        if (relays.none { it.displayUrl() == url.displayUrl() }) {
            relays.add(url)
        }
        relayText = TextFieldValue()
        keyboardController?.hide()
    }

    fun parseAuthorOrNull(): String? {
        val text = account.text.trim()
        if (text.isBlank()) return ""
        if (text.startsWith("npub")) {
            return when (val parsed = Nip19Parser.uriToRoute(text)?.entity) {
                is NPub -> parsed.hex
                else -> null
            }
        }
        return try {
            if (Hex.decode(text).size == 32) text.lowercase() else null
        } catch (_: Exception) {
            null
        }
    }

    fun loadAuthorRelays() {
        val author = parseAuthorOrNull()
        if (author == null) {
            toast(context.getString(R.string.rebroadcast_invalid_account))
            return
        }
        if (author.isBlank()) {
            toast(context.getString(R.string.rebroadcast_account_required))
            return
        }
        loadingUserRelays = true
        scope.launch(Dispatchers.IO) {
            try {
                val database = AppDatabase.getDatabase(context)
                val signer = NostrSignerInternal(KeyPair(pubKey = Hex.decode(author)))

                var advertised = database.eventDao().getAdvertisedRelayList(author)?.toEvent() as? AdvertisedRelayListEvent
                if (advertised == null) {
                    advertised = EventDownloader.fetchAdvertisedRelayList(signer)
                }
                val found = mutableListOf<NormalizedRelayUrl>()
                advertised?.writeRelays()?.forEach { url ->
                    RelayUrlNormalizer.normalizeOrNull(url)?.let { found.add(it) }
                }

                if (found.isEmpty()) {
                    var contactList = database.eventDao().getContactList(author)?.toEvent() as? ContactListEvent
                    if (contactList == null) {
                        contactList = EventDownloader.fetchContactList(signer)
                    }
                    contactList?.relays()?.forEach { relay ->
                        RelayUrlNormalizer.normalizeOrNull(relay.key.url)?.let { found.add(it) }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (found.isEmpty()) {
                        toast(context.getString(R.string.no_relays_found))
                    } else {
                        addRelays(found)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.no_relays_found))
                }
            } finally {
                loadingUserRelays = false
            }
        }
    }

    fun parseDateOrNull(text: String, endOfDay: Boolean): Long? = try {
        val date = LocalDate.parse(text.trim())
        val zone = ZoneId.systemDefault()
        if (endOfDay) {
            date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        } else {
            date.atStartOfDay(zone).toEpochSecond()
        }
    } catch (_: DateTimeParseException) {
        null
    }

    fun validate(): RebroadcastRequest? {
        if (relays.isEmpty()) {
            toast(context.getString(R.string.rebroadcast_no_relays))
            return null
        }

        val author = parseAuthorOrNull()
        if (author == null) {
            toast(context.getString(R.string.rebroadcast_invalid_account))
            return null
        }

        val kindTokens = kindsText.text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val kinds = kindTokens.mapNotNull { it.toIntOrNull() }.toSet()
        if (kinds.size != kindTokens.size) {
            toast(context.getString(R.string.invalid_kind))
            return null
        }

        val since = if (fromDateText.text.isBlank()) {
            null
        } else {
            parseDateOrNull(fromDateText.text, endOfDay = false) ?: run {
                toast(context.getString(R.string.rebroadcast_invalid_date))
                return null
            }
        }
        val until = if (toDateText.text.isBlank()) {
            null
        } else {
            parseDateOrNull(toDateText.text, endOfDay = true) ?: run {
                toast(context.getString(R.string.rebroadcast_invalid_date))
                return null
            }
        }
        if (since != null && until != null && since > until) {
            toast(context.getString(R.string.rebroadcast_invalid_date_range))
            return null
        }

        return RebroadcastRequest(
            relays = relays.toList(),
            authorPubKey = author.ifBlank { null },
            kinds = kinds,
            since = since,
            until = until,
            includePrivateKinds = includePrivateKinds,
        )
    }

    val launcherLogin = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                toast(context.getString(R.string.sign_request_rejected))
            } else {
                result.data?.let {
                    try {
                        val key = it.getStringExtra("signature") ?: ""
                        val returnedKey = if (key.startsWith("npub")) {
                            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                                is NPub -> parsed.hex
                                else -> ""
                            }
                        } else {
                            key
                        }
                        if (returnedKey.isNotBlank()) {
                            account = TextFieldValue(Hex.decode(returnedKey).toNpub())
                        }
                    } catch (e: Exception) {
                        Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        },
    )

    fun loginWithExternalSigner() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
            intent.putExtra("type", "get_public_key")
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launcherLogin.launch(intent)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.message ?: "", e)
            toast(context.getString(R.string.no_external_signer_installed))
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
            launcherLogin.launch(intent)
        }
    }

    pendingRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingRequest = null },
            title = { Text(stringResource(R.string.rebroadcast_events)) },
            text = { Text(stringResource(R.string.rebroadcast_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRequest = null
                        val database = AppDatabase.getDatabase(context)
                        Citrine.instance.cancelJob()
                        Citrine.job = Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                            EventRebroadcaster.rebroadcast(
                                database = database,
                                relays = request.relays,
                                authorPubKey = request.authorPubKey,
                                kinds = request.kinds,
                                since = request.since,
                                until = request.until,
                                includePrivateKinds = request.includePrivateKinds,
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRequest = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val running = state.status == EventRebroadcaster.Status.RUNNING

        Text(stringResource(R.string.rebroadcast_description))

        if (!running) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = account,
                onValueChange = { account = it },
                label = { Text(stringResource(R.string.rebroadcast_account)) },
                supportingText = { Text(stringResource(R.string.rebroadcast_account_hint)) },
            )
            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { loginWithExternalSigner() },
            ) {
                Text(stringResource(R.string.login_with_external_signer))
            }
            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !loadingUserRelays && account.text.isNotBlank(),
                onClick = { loadAuthorRelays() },
            ) {
                if (loadingUserRelays) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    Text(stringResource(R.string.rebroadcast_use_author_relays))
                }
            }
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = kindsText,
                onValueChange = { kindsText = it },
                label = { Text(stringResource(R.string.rebroadcast_kinds)) },
                supportingText = { Text(stringResource(R.string.rebroadcast_kinds_hint)) },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = fromDateText,
                onValueChange = { fromDateText = it },
                label = { Text(stringResource(R.string.rebroadcast_from_date)) },
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = toDateText,
                onValueChange = { toDateText = it },
                label = { Text(stringResource(R.string.rebroadcast_to_date)) },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { includePrivateKinds = !includePrivateKinds },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includePrivateKinds,
                    onCheckedChange = { includePrivateKinds = it },
                )
                Text(stringResource(R.string.rebroadcast_include_private))
            }
            Text(
                stringResource(
                    R.string.rebroadcast_include_private_description,
                    KINDS_PRIVATE_EVENTS.sorted().joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = relayText,
                onValueChange = { relayText = it },
                label = { Text(stringResource(R.string.wss)) },
                supportingText = { Text(stringResource(R.string.rebroadcast_relays_hint)) },
                keyboardOptions = KeyboardOptions.Default.copy(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onDone = { addRelay() }),
            )
            relays.forEachIndexed { index, relay ->
                RelayCard(
                    relay = relay.url,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { relays.removeAt(index) },
                )
            }

            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    addRelay()
                    validate()?.let { pendingRequest = it }
                },
            ) {
                Text(stringResource(R.string.rebroadcast_start))
            }
        }

        if (state.status != EventRebroadcaster.Status.IDLE) {
            RebroadcastProgress(state)
        }

        if (running) {
            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { Citrine.instance.cancelJob() },
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun RebroadcastProgress(state: EventRebroadcaster.State) {
    val statusText = when (state.status) {
        EventRebroadcaster.Status.RUNNING -> stringResource(R.string.rebroadcast_running)
        EventRebroadcaster.Status.FINISHED -> stringResource(R.string.rebroadcast_finished)
        EventRebroadcaster.Status.CANCELLED -> stringResource(R.string.rebroadcast_cancelled)
        EventRebroadcaster.Status.FAILED -> stringResource(R.string.rebroadcast_failed)
        EventRebroadcaster.Status.IDLE -> return
    }

    Text(statusText, style = MaterialTheme.typography.titleMedium)

    if (state.totalEvents > 0) {
        LinearProgressIndicator(
            progress = { state.processedEvents.toFloat() / state.totalEvents },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.rebroadcast_progress, state.processedEvents, state.totalEvents))
    } else {
        Text(stringResource(R.string.rebroadcast_no_events))
    }

    Text(stringResource(R.string.rebroadcast_counts_hint), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.rebroadcast_accepted, state.accepted))
    Text(stringResource(R.string.rebroadcast_duplicates, state.duplicates))
    Text(stringResource(R.string.rebroadcast_rejected, state.rejected))
    Text(stringResource(R.string.rebroadcast_no_response, state.failed))

    state.skippedRelays.forEach {
        Text(
            stringResource(R.string.rebroadcast_relay_unreachable, it),
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (state.recentRejections.isNotEmpty()) {
        Text(
            stringResource(R.string.rebroadcast_recent_rejections),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        state.recentRejections.reversed().forEach {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
