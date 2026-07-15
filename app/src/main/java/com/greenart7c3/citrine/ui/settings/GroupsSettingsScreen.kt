package com.greenart7c3.citrine.ui.settings

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.RelayIdentity
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GroupsSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current

    Surface(modifier) {
        var nip29Enabled by remember { mutableStateOf(Settings.nip29Enabled) }

        // Reading the relay key can generate it on first access, so it stays off the
        // main thread.
        val relayNpub by produceState(initialValue = "") {
            value = withContext(Dispatchers.IO) {
                try {
                    Hex.decode(RelayIdentity.pubKeyHex(context.applicationContext)).toNpub()
                } catch (_: Exception) {
                    ""
                }
            }
        }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.nip29Enabled = nip29Enabled
                LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
                onApplyChanges()
                delay(1500)
                isLoading = false
            }
            Unit
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SwitchSettingRow(
                        title = stringResource(R.string.enable_nip29_groups),
                        description = stringResource(R.string.enable_nip29_groups_description),
                        checked = nip29Enabled,
                        onCheckedChange = { nip29Enabled = it },
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.nip29_groups_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                item {
                    SectionHeader(stringResource(R.string.nip29_relay_identity))
                }
                item {
                    Text(
                        text = stringResource(R.string.nip29_relay_identity_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                item {
                    Text(
                        text = relayNpub.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = relayNpub.isNotBlank()) {
                                scope.launch {
                                    clipboardManager.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("npub", relayNpub)),
                                    )
                                    Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
