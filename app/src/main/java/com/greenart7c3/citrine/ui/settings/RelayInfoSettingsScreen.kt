package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
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
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RelayInfoSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Surface(modifier) {
        var host by remember { mutableStateOf(TextFieldValue(Settings.host)) }
        var port by remember { mutableStateOf(TextFieldValue(Settings.port.toString())) }
        var relayName by remember { mutableStateOf(TextFieldValue(Settings.name)) }
        var relayOwnerPubkey by remember { mutableStateOf(TextFieldValue(Settings.ownerPubkey)) }
        var relayContact by remember { mutableStateOf(TextFieldValue(Settings.contact)) }
        var relayDescription by remember { mutableStateOf(TextFieldValue(Settings.description)) }
        var relayIconUrl by remember { mutableStateOf(TextFieldValue(Settings.relayIcon)) }

        val applyChanges: () -> Unit = {
            if (!isIpValid(host.text) || host.text.isBlank()) {
                Toast.makeText(context, context.getString(R.string.invalid_host_or_port), Toast.LENGTH_SHORT).show()
            } else if (port.text.isBlank() || !port.text.isDigitsOnly()) {
                Toast.makeText(context, context.getString(R.string.invalid_host_or_port), Toast.LENGTH_SHORT).show()
            } else if (relayOwnerPubkey.text.isNotBlank() && relayOwnerPubkey.text.toNostrKey() == null) {
                Toast.makeText(context, context.getString(R.string.invalid_owner_pubkey), Toast.LENGTH_SHORT).show()
            } else if (relayIconUrl.text.isNotBlank() && runCatching { relayIconUrl.text.toUri() }.isFailure) {
                Toast.makeText(context, context.getString(R.string.invalid_icon_url), Toast.LENGTH_SHORT).show()
            } else {
                scope.launch(Dispatchers.IO) {
                    isLoading = true
                    Settings.host = host.text
                    Settings.port = port.text.toInt()
                    Settings.name = relayName.text
                    Settings.ownerPubkey = relayOwnerPubkey.text.toNostrKey() ?: ""
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
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        value = relayOwnerPubkey,
                        label = { Text(stringResource(R.string.relay_owner_pubkey)) },
                        onValueChange = { relayOwnerPubkey = it },
                        singleLine = true,
                    )
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
