package com.greenart7c3.citrine.ui.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.nip29.Nip29
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.utils.Hex

private val KINDS_THE_RELAY_SIGNS_WITH_THE_OWNER_KEY = listOf(
    Nip29.KIND_PUT_USER,
    Nip29.KIND_REMOVE_USER,
    Nip29.KIND_GROUP_METADATA,
    Nip29.KIND_GROUP_ADMINS,
    Nip29.KIND_GROUP_MEMBERS,
    Nip29.KIND_GROUP_ROLES,
)

@Composable
fun RelayInfoSettingsScreen(
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RelayInfoSettingsViewModel = viewModel(factory = RelayInfoSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val context = LocalContext.current

    Surface(modifier) {
        val relayOwnerPubkey = viewModel.relayOwnerPubkey
        val ownerNpub = remember(relayOwnerPubkey) { npubOrBlank(relayOwnerPubkey) }

        val loginWithRelaySigner = rememberRelaySignerLogin(onLogin = viewModel::loginWithExternalSigner)

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                connectionItems(
                    host = viewModel.host,
                    onHostChange = { viewModel.host = it },
                    port = viewModel.port,
                    onPortChange = { viewModel.port = it },
                    relayName = viewModel.relayName,
                    onRelayNameChange = { viewModel.relayName = it },
                )
                ownerItems(
                    ownerNpub = ownerNpub,
                    isOwnerConfigured = relayOwnerPubkey.isNotBlank(),
                    onLogin = loginWithRelaySigner,
                    onLogout = viewModel::logout,
                )
                detailItems(
                    relayContact = viewModel.relayContact,
                    onRelayContactChange = { viewModel.relayContact = it },
                    relayDescription = viewModel.relayDescription,
                    onRelayDescriptionChange = { viewModel.relayDescription = it },
                    relayIconUrl = viewModel.relayIconUrl,
                    onRelayIconUrlChange = { viewModel.relayIconUrl = it },
                    maxEventSize = viewModel.maxEventSize,
                    onMaxEventSizeChange = { viewModel.maxEventSize = it },
                )
            }

            SettingsApplyBar(
                enabled = !viewModel.isLoading,
                onApply = {
                    viewModel.applyChanges(onApplyChanges)?.let { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

@Composable
private fun rememberRelaySignerLogin(onLogin: (key: String, packageName: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noSignerInstalledMsg = stringResource(R.string.no_external_signer_installed)

    val relaySignerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
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
                    onLogin(key, packageName)
                } catch (e: Exception) {
                    Log.d(Citrine.TAG, e.message ?: "", e)
                }
            }
        }
    }

    return {
        try {
            relaySignerLauncher.launch(relaySignerLoginIntent())
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.message ?: "", e)
            Toast.makeText(
                context,
                noSignerInstalledMsg,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

@Suppress("NoComments")
private fun npubOrBlank(hexPubkey: String): String {
    // Hex.decode("") yields an empty array rather than throwing, and bech32
    // happily encodes zero bytes into a stub like "npub106246s" — so an
    // unconfigured owner must be caught before decoding.
    return if (hexPubkey.isBlank()) {
        ""
    } else {
        runCatching { Hex.decode(hexPubkey).toNpub() }.getOrDefault("")
    }
}

private fun relaySignerLoginIntent(): Intent {
    val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
    intent.putExtra("type", "get_public_key")
    val permissions = KINDS_THE_RELAY_SIGNS_WITH_THE_OWNER_KEY.joinToString(",") {
        Permission(CommandType.SIGN_EVENT, it).toJson()
    }
    intent.putExtra("permissions", "[$permissions]")
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return intent
}

private fun LazyListScope.connectionItems(
    host: TextFieldValue,
    onHostChange: (TextFieldValue) -> Unit,
    port: TextFieldValue,
    onPortChange: (TextFieldValue) -> Unit,
    relayName: TextFieldValue,
    onRelayNameChange: (TextFieldValue) -> Unit,
) {
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = host,
            label = { Text(stringResource(R.string.host)) },
            onValueChange = onHostChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = port,
            label = { Text(stringResource(R.string.port)) },
            onValueChange = onPortChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = relayName,
            label = { Text(stringResource(R.string.relay_name)) },
            onValueChange = onRelayNameChange,
            singleLine = true,
        )
    }
}

private fun LazyListScope.ownerItems(
    ownerNpub: String,
    isOwnerConfigured: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    item {
        SectionHeader(stringResource(R.string.relay_owner_pubkey))
    }
    item {
        Text(
            text = stringResource(R.string.relay_owner_login_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    item {
        Text(
            text = ownerNpub.ifBlank { stringResource(R.string.relay_owner_not_configured) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedButton(onClick = onLogin) {
                Text(stringResource(R.string.login_with_external_signer))
            }
            if (isOwnerConfigured) {
                ElevatedButton(onClick = onLogout) {
                    Text(stringResource(R.string.logout))
                }
            }
        }
    }
}

private fun LazyListScope.detailItems(
    relayContact: TextFieldValue,
    onRelayContactChange: (TextFieldValue) -> Unit,
    relayDescription: TextFieldValue,
    onRelayDescriptionChange: (TextFieldValue) -> Unit,
    relayIconUrl: TextFieldValue,
    onRelayIconUrlChange: (TextFieldValue) -> Unit,
    maxEventSize: TextFieldValue,
    onMaxEventSizeChange: (TextFieldValue) -> Unit,
) {
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = relayContact,
            label = { Text(stringResource(R.string.relay_contact)) },
            onValueChange = onRelayContactChange,
            singleLine = true,
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = relayDescription,
            label = { Text(stringResource(R.string.relay_description)) },
            onValueChange = onRelayDescriptionChange,
            minLines = 2,
            maxLines = 4,
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = relayIconUrl,
            label = { Text(stringResource(R.string.relay_icon_url)) },
            onValueChange = onRelayIconUrlChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
    item {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            value = maxEventSize,
            label = { Text(stringResource(R.string.max_event_size)) },
            supportingText = { Text(stringResource(R.string.max_event_size_description)) },
            onValueChange = onMaxEventSizeChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
