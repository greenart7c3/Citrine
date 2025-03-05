package com.greenart7c3.citrine.ui

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.greenart7c3.citrine.R
import com.vitorpamplona.quartz.signers.NostrSigner
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
    var shouldShowDialog by remember { mutableStateOf(false) }
    var npub by remember { mutableStateOf(TextFieldValue()) }
    val signer: NostrSigner? = null
    var relays = remember { mutableListOf<String>() }

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
            onClick = { },
        )
        ElevatedButton(
            content = {
                Text(stringResource(R.string.fetch_events))
            },
            onClick = {
                shouldShowDialog = true
            },
        )
    }
}
