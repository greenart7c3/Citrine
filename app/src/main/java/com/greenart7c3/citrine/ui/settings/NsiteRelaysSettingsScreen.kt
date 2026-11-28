package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import kotlinx.coroutines.launch

@Composable
fun NsiteRelaysSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: NsiteRelaysSettingsViewModel = viewModel(factory = NsiteRelaysSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val invalidRelayMsg = stringResource(R.string.relay_aggregator_invalid_relay)

    Surface(modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                nsiteRelayItems(
                    input = viewModel.nsiteRelayInput,
                    onInputChange = { viewModel.nsiteRelayInput = it },
                    onPaste = {
                        scope.launch {
                            val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                            viewModel.nsiteRelayInput = TextFieldValue(text)
                        }
                    },
                    onAdd = {
                        if (!viewModel.addNsiteRelay()) {
                            Toast.makeText(context, invalidRelayMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onReset = viewModel::resetNsiteRelays,
                    relays = viewModel.nsiteRelays,
                    onDelete = viewModel::removeNsiteRelay,
                )
            }

            SettingsApplyBar(enabled = !viewModel.isLoading, onApply = viewModel::applyChanges)
        }
    }
}

private fun LazyListScope.nsiteRelayItems(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    onReset: () -> Unit,
    relays: Set<String>,
    onDelete: (String) -> Unit,
) {
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
            value = input,
            onValueChange = onInputChange,
            onPaste = onPaste,
            onAdd = onAdd,
        )
    }
    item {
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.reset_to_default))
        }
    }
    if (relays.isEmpty()) {
        item {
            EmptyListHint(stringResource(R.string.nsite_relays_hint))
        }
    }
    items(relays.toList()) { relay ->
        PubkeyListItem(
            text = relay,
            onDelete = { onDelete(relay) },
        )
    }
}
