package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NsiteRelaysSettingsScreen(
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val invalidRelayMsg = stringResource(R.string.relay_aggregator_invalid_relay)

    Surface(modifier) {
        var nsiteRelays by remember { mutableStateOf(Settings.nsiteRelays) }
        var nsiteRelayInput by remember { mutableStateOf(TextFieldValue("")) }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.nsiteRelays = nsiteRelays
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                delay(800)
                isLoading = false
            }
            Unit
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Text(
                        text = stringResource(R.string.nsite_relays_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                item {
                    PubkeyInputRow(
                        value = nsiteRelayInput,
                        onValueChange = { nsiteRelayInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                nsiteRelayInput = TextFieldValue(text)
                            }
                        },
                        onAdd = {
                            val normalized = normalizeRelayInput(nsiteRelayInput.text)
                            if (normalized == null) {
                                Toast.makeText(context, invalidRelayMsg, Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            nsiteRelays = nsiteRelays + normalized
                            nsiteRelayInput = TextFieldValue("")
                        },
                    )
                }
                item {
                    TextButton(onClick = { nsiteRelays = Settings.DEFAULT_NSITE_RELAYS }) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }
                if (nsiteRelays.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.nsite_relays_hint))
                    }
                }
                items(nsiteRelays.toList()) { relay ->
                    PubkeyListItem(
                        text = relay,
                        onDelete = { nsiteRelays = nsiteRelays - relay },
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
