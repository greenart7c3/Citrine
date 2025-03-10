package com.greenart7c3.citrine.ui

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.vitorpamplona.quartz.crypto.KeyPair
import com.vitorpamplona.quartz.encoders.Hex
import com.vitorpamplona.quartz.encoders.Nip19Bech32
import com.vitorpamplona.quartz.signers.ExternalSignerLauncher
import com.vitorpamplona.quartz.signers.NostrSigner
import com.vitorpamplona.quartz.signers.NostrSignerExternal
import com.vitorpamplona.quartz.signers.NostrSignerInternal
import com.vitorpamplona.quartz.signers.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectRelayModal(
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var relayText by remember { mutableStateOf(TextFieldValue()) }
    var relays = remember { mutableListOf<String>() }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        modifier = Modifier
            .fillMaxSize(),
        content = {
            val keyboardController = LocalSoftwareKeyboardController.current
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions.Default.copy(
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            relays = (relays + relayText.text).toMutableList()
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
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(relays) { Text(it) }
                }
                ElevatedButton(
                    content = {
                        Text(stringResource(R.string.fetch_events))
                    },
                    onClick = {
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
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var shouldShowDialog by remember { mutableStateOf(false) }
    var npub by remember { mutableStateOf(TextFieldValue()) }
    var signer: NostrSigner? = null
    var relays = remember { mutableListOf<String>() }

    val launcherLogin = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(
                    context,
                    context.getString(R.string.sign_request_rejected),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                result.data?.let {
                    try {
                        val key = it.getStringExtra("signature") ?: ""
                        val packageName = it.getStringExtra("package") ?: ""

                        val returnedKey = if (key.startsWith("npub")) {
                            when (val parsed = Nip19Bech32.uriToRoute(key)?.entity) {
                                is Nip19Bech32.NPub -> parsed.hex
                                else -> ""
                            }
                        } else {
                            key
                        }

                        signer = NostrSignerExternal(
                            returnedKey,
                            ExternalSignerLauncher(returnedKey, packageName),
                        )
                    } catch (e: Exception) {
                        Log.d(Citrine.TAG, e.message ?: "", e)
                    }
                }
            }
        },
    )

    if (shouldShowDialog) {
        SelectRelayModal(
            onDone = {
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
                                "sign_event",
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
                            context.getString(R.string.no_external_signer_installed),
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
                    val entity = Nip19Bech32.uriToRoute(npub.text)

                    if (entity == null) {
                        Toast.makeText(
                            context,
                            "Invalid account",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@ElevatedButton
                    }

                    if (entity.entity !is Nip19Bech32.NPub) {
                        Toast.makeText(
                            context,
                            "Invalid account",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@ElevatedButton
                    }

                    signer = NostrSignerInternal(
                        KeyPair(
                            pubKey = Hex.decode((entity.entity as Nip19Bech32.NPub).hex),
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
