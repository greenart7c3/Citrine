package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.EventRebroadcaster
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.utils.Hex
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RebroadcastScreen(
    modifier: Modifier = Modifier,
    viewModel: RebroadcastViewModel = viewModel(factory = RebroadcastViewModel.factory(LocalContext.current.applicationContext, LocalAppScope.current)),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val relayClient = LocalRelayClient.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val state by EventRebroadcaster.state.collectAsStateWithLifecycle()

    val form = remember { RebroadcastFormState() }
    var pendingRequest by remember { mutableStateOf<RebroadcastRequest?>(null) }
    val scope = rememberCoroutineScope()

    fun toast(@StringRes message: Int) {
        Toast.makeText(context, resources.getString(message), Toast.LENGTH_SHORT).show()
    }

    fun addRelay() {
        form.addRelay(
            onInvalid = { toast(R.string.relay_aggregator_invalid_relay) },
            onAdded = { keyboardController?.hide() },
        )
    }

    val loginWithExternalSigner = rememberSignerAccountLogin { form.account = TextFieldValue(it) }

    pendingRequest?.let { request ->
        RebroadcastConfirmDialog(
            onConfirm = {
                pendingRequest = null
                viewModel.rebroadcast(request)
            },
            onDismiss = { pendingRequest = null },
        )
    }

    RebroadcastContent(
        state = state,
        form = form,
        onLogin = loginWithExternalSigner,
        onLoadAuthorRelays = {
            form.loadAuthorRelays(
                scope = scope,
                authorRelays = { viewModel.authorRelays(it) },
                isPrivateIp = { relayClient.isPrivateIp(it) },
                onMessage = { toast(it) },
            )
        },
        onAddRelay = { addRelay() },
        onStart = {
            addRelay()
            when (val result = form.validate()) {
                is RebroadcastValidation.Valid -> pendingRequest = result.request
                is RebroadcastValidation.Invalid -> toast(result.message)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun RebroadcastContent(
    state: EventRebroadcaster.State,
    form: RebroadcastFormState,
    onLogin: () -> Unit,
    onLoadAuthorRelays: () -> Unit,
    onAddRelay: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val running = state.status == EventRebroadcaster.Status.RUNNING

        Text(stringResource(R.string.rebroadcast_description))

        if (!running) {
            AccountFields(
                form = form,
                onLogin = onLogin,
                onLoadAuthorRelays = onLoadAuthorRelays,
            )
            FilterFields(form = form)
            RelayFields(form = form, onAddRelay = onAddRelay)

            ElevatedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onStart,
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
                onClick = { Citrine.cancelJob() },
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

private class RebroadcastFormState {
    var account by mutableStateOf(TextFieldValue())
    var kindsText by mutableStateOf(TextFieldValue())
    var fromDateText by mutableStateOf(TextFieldValue())
    var toDateText by mutableStateOf(TextFieldValue())
    var includePrivateKinds by mutableStateOf(false)
    var relayText by mutableStateOf(TextFieldValue())
    val relays = listOf<NormalizedRelayUrl>().toMutableStateList()
    var loadingUserRelays by mutableStateOf(false)

    fun addRelay(onInvalid: () -> Unit, onAdded: () -> Unit) {
        if (relayText.text.isBlank()) return
        val url = RelayUrlNormalizer.normalizeOrNull(relayText.text)
        if (url == null) {
            onInvalid()
            return
        }
        if (relays.none { it.displayUrl() == url.displayUrl() }) {
            relays.add(url)
        }
        relayText = TextFieldValue()
        onAdded()
    }

    fun loadAuthorRelays(
        scope: CoroutineScope,
        authorRelays: suspend (String) -> List<NormalizedRelayUrl>,
        isPrivateIp: (String) -> Boolean,
        onMessage: (Int) -> Unit,
    ) {
        val author = parseAuthorOrNull(account.text)
        if (author == null) {
            onMessage(R.string.rebroadcast_invalid_account)
            return
        }
        if (author.isBlank()) {
            onMessage(R.string.rebroadcast_account_required)
            return
        }
        val onNoRelaysFound = { onMessage(R.string.no_relays_found) }
        loadingUserRelays = true
        scope.launch(Dispatchers.IO) {
            try {
                val found = authorRelays(author)

                withContext(Dispatchers.Main) {
                    if (found.isEmpty()) {
                        onNoRelaysFound()
                    } else {
                        addRelays(found, isPrivateIp)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.d(Citrine.TAG, e.message ?: "", e)
                withContext(Dispatchers.Main) {
                    onNoRelaysFound()
                }
            } finally {
                loadingUserRelays = false
            }
        }
    }

    private fun addRelays(found: List<NormalizedRelayUrl>, isPrivateIp: (String) -> Boolean) {
        found.forEach { url ->
            if (!isPrivateIp(url.url) && relays.none { it.displayUrl() == url.displayUrl() }) {
                relays.add(url)
            }
        }
    }

    fun validate(): RebroadcastValidation {
        val author = parseAuthorOrNull(account.text)
        val kindTokens = kindsText.text.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val kinds = kindTokens.mapNotNull { it.toIntOrNull() }.toSet()
        val since = fromDateText.text.takeIf { it.isNotBlank() }?.let { parseDateOrNull(it, endOfDay = false) }
        val until = toDateText.text.takeIf { it.isNotBlank() }?.let { parseDateOrNull(it, endOfDay = true) }
        val invalidSince = fromDateText.text.isNotBlank() && since == null
        val invalidUntil = toDateText.text.isNotBlank() && until == null

        val error = when {
            relays.isEmpty() -> R.string.rebroadcast_no_relays
            author == null -> R.string.rebroadcast_invalid_account
            kinds.size != kindTokens.size -> R.string.invalid_kind
            invalidSince || invalidUntil -> R.string.rebroadcast_invalid_date
            since != null && until != null && since > until -> R.string.rebroadcast_invalid_date_range
            else -> null
        }
        if (error != null) return RebroadcastValidation.Invalid(error)

        return RebroadcastValidation.Valid(
            RebroadcastRequest(
                relays = relays.toList(),
                authorPubKey = author?.ifBlank { null },
                kinds = kinds,
                since = since,
                until = until,
                includePrivateKinds = includePrivateKinds,
            ),
        )
    }
}

private sealed interface RebroadcastValidation {
    class Valid(val request: RebroadcastRequest) : RebroadcastValidation
    class Invalid(@StringRes val message: Int) : RebroadcastValidation
}

private fun parseAuthorOrNull(account: String): String? {
    val text = account.trim()
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

private fun parseDateOrNull(text: String, endOfDay: Boolean): Long? = try {
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

@Composable
private fun rememberSignerAccountLogin(onAccount: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noExternalSignerMsg = stringResource(R.string.no_external_signer_installed)

    val launcherLogin = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
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
                            onAccount(Hex.decode(returnedKey).toNpub())
                        }
                    } catch (e: Exception) {
                        Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        },
    )

    return {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
            intent.putExtra("type", "get_public_key")
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launcherLogin.launch(intent)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.message ?: "", e)
            Toast.makeText(context, noExternalSignerMsg, Toast.LENGTH_SHORT).show()
            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/greenart7c3/Amber/releases".toUri())
            launcherLogin.launch(intent)
        }
    }
}

@Composable
private fun RebroadcastConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rebroadcast_events)) },
        text = { Text(stringResource(R.string.rebroadcast_warning)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ColumnScope.AccountFields(
    form: RebroadcastFormState,
    onLogin: () -> Unit,
    onLoadAuthorRelays: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = form.account,
        onValueChange = { form.account = it },
        label = { Text(stringResource(R.string.rebroadcast_account)) },
        supportingText = { Text(stringResource(R.string.rebroadcast_account_hint)) },
    )
    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onLogin,
    ) {
        Text(stringResource(R.string.login_with_external_signer))
    }
    ElevatedButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = !form.loadingUserRelays && form.account.text.isNotBlank(),
        onClick = onLoadAuthorRelays,
    ) {
        if (form.loadingUserRelays) {
            CircularProgressIndicator(Modifier.size(20.dp))
        } else {
            Text(stringResource(R.string.rebroadcast_use_author_relays))
        }
    }
}

@Composable
private fun ColumnScope.FilterFields(form: RebroadcastFormState) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = form.kindsText,
        onValueChange = { form.kindsText = it },
        label = { Text(stringResource(R.string.rebroadcast_kinds)) },
        supportingText = { Text(stringResource(R.string.rebroadcast_kinds_hint)) },
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = form.fromDateText,
        onValueChange = { form.fromDateText = it },
        label = { Text(stringResource(R.string.rebroadcast_from_date)) },
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = form.toDateText,
        onValueChange = { form.toDateText = it },
        label = { Text(stringResource(R.string.rebroadcast_to_date)) },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { form.includePrivateKinds = !form.includePrivateKinds },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = form.includePrivateKinds,
            onCheckedChange = { form.includePrivateKinds = it },
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
}

@Composable
private fun ColumnScope.RelayFields(
    form: RebroadcastFormState,
    onAddRelay: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = form.relayText,
        onValueChange = { form.relayText = it },
        label = { Text(stringResource(R.string.wss)) },
        supportingText = { Text(stringResource(R.string.rebroadcast_relays_hint)) },
        keyboardOptions = KeyboardOptions.Default.copy(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
            capitalization = KeyboardCapitalization.None,
        ),
        keyboardActions = KeyboardActions(onDone = { onAddRelay() }),
    )
    form.relays.forEachIndexed { index, relay ->
        RelayCard(
            relay = relay.url,
            modifier = Modifier.fillMaxWidth(),
            onClick = { form.relays.removeAt(index) },
        )
    }
}

@Composable
private fun ColumnScope.RebroadcastProgress(state: EventRebroadcaster.State) {
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
