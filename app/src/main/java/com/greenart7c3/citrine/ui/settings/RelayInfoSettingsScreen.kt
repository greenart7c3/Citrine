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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.server.nip29.Nip29
import com.greenart7c3.citrine.service.LocalPreferences
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Kinds the relay signs with the owner's key: relay-issued membership changes and the
// NIP-29 group metadata events. Requested at login so Amber can auto-approve them.
private val RELAY_SIGNER_KINDS = listOf(
    Nip29.KIND_PUT_USER,
    Nip29.KIND_REMOVE_USER,
    Nip29.KIND_GROUP_METADATA,
    Nip29.KIND_GROUP_ADMINS,
    Nip29.KIND_GROUP_MEMBERS,
    Nip29.KIND_GROUP_ROLES,
)

@Composable
fun RelayInfoSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val invalidHostOrPortMsg = stringResource(R.string.invalid_host_or_port)
    val invalidIconUrlMsg = stringResource(R.string.invalid_icon_url)
    val noSignerInstalledMsg = stringResource(R.string.no_external_signer_installed)

    Surface(modifier) {
        var host by remember { mutableStateOf(TextFieldValue(Settings.host)) }
        var port by remember { mutableStateOf(TextFieldValue(Settings.port.toString())) }
        var relayName by remember { mutableStateOf(TextFieldValue(Settings.name)) }
        var relayOwnerPubkey by remember { mutableStateOf(Settings.ownerPubkey) }
        var relaySignerPackageName by remember { mutableStateOf(Settings.relaySignerPackageName) }
        var relayContact by remember { mutableStateOf(TextFieldValue(Settings.contact)) }
        var relayDescription by remember { mutableStateOf(TextFieldValue(Settings.description)) }
        var relayIconUrl by remember { mutableStateOf(TextFieldValue(Settings.relayIcon)) }

        val ownerNpub = remember(relayOwnerPubkey) {
            // Hex.decode("") yields an empty array rather than throwing, and bech32
            // happily encodes zero bytes into a stub like "npub106246s" — so an
            // unconfigured owner must be caught before decoding.
            if (relayOwnerPubkey.isBlank()) {
                ""
            } else {
                runCatching { Hex.decode(relayOwnerPubkey).toNpub() }.getOrDefault("")
            }
        }

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
                        val returnedKey = if (key.startsWith("npub")) {
                            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                                is NPub -> parsed.hex
                                else -> ""
                            }
                        } else {
                            key
                        }
                        if (returnedKey.isBlank() || packageName.isBlank()) return@let
                        relayOwnerPubkey = returnedKey
                        relaySignerPackageName = packageName
                    } catch (e: Exception) {
                        Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        }

        val applyChanges: () -> Unit = {
            if (!isIpValid(host.text) || host.text.isBlank()) {
                Toast.makeText(context, invalidHostOrPortMsg, Toast.LENGTH_SHORT).show()
            } else if (port.text.isBlank() || !port.text.isDigitsOnly()) {
                Toast.makeText(context, invalidHostOrPortMsg, Toast.LENGTH_SHORT).show()
            } else if (relayIconUrl.text.isNotBlank() && runCatching { relayIconUrl.text.toUri() }.isFailure) {
                Toast.makeText(context, invalidIconUrlMsg, Toast.LENGTH_SHORT).show()
            } else {
                scope.launch(Dispatchers.IO) {
                    isLoading = true
                    Settings.host = host.text
                    Settings.port = port.text.toInt()
                    Settings.name = relayName.text
                    Settings.ownerPubkey = relayOwnerPubkey
                    Settings.relaySignerPackageName = relaySignerPackageName
                    Settings.contact = relayContact.text
                    Settings.description = relayDescription.text
                    Settings.relayIcon = relayIconUrl.text
                    LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                    onApplyChanges()
                    delay(1500)
                    isLoading = false
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = host,
                        label = { Text(stringResource(R.string.host)) },
                        onValueChange = { host = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = port,
                        label = { Text(stringResource(R.string.port)) },
                        onValueChange = { port = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = relayName,
                        label = { Text(stringResource(R.string.relay_name)) },
                        onValueChange = { relayName = it },
                        singleLine = true,
                    )
                }

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
                        ElevatedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
                                    intent.putExtra("type", "get_public_key")
                                    val permissions = RELAY_SIGNER_KINDS.joinToString(",") {
                                        Permission(CommandType.SIGN_EVENT, it).toJson()
                                    }
                                    intent.putExtra("permissions", "[$permissions]")
                                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    relaySignerLauncher.launch(intent)
                                } catch (e: Exception) {
                                    Log.d(Citrine.TAG, e.message ?: "", e)
                                    Toast.makeText(
                                        context,
                                        noSignerInstalledMsg,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.login_with_external_signer))
                        }
                        if (relayOwnerPubkey.isNotBlank()) {
                            ElevatedButton(
                                onClick = {
                                    relayOwnerPubkey = ""
                                    relaySignerPackageName = ""
                                },
                            ) {
                                Text(stringResource(R.string.logout))
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = relayContact,
                        label = { Text(stringResource(R.string.relay_contact)) },
                        onValueChange = { relayContact = it },
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = relayDescription,
                        label = { Text(stringResource(R.string.relay_description)) },
                        onValueChange = { relayDescription = it },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = relayIconUrl,
                        label = { Text(stringResource(R.string.relay_icon_url)) },
                        onValueChange = { relayIconUrl = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
