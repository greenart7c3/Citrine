@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SettingsRow
import com.greenart7c3.citrine.ui.components.TitleExplainer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

@Composable
fun RetentionSettingsScreen(
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RetentionSettingsViewModel = viewModel(factory = RetentionSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val invalidKeyMsg = stringResource(R.string.invalid_key)
    val invalidKindMsg = stringResource(R.string.invalid_kind)

    Surface(modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                olderThanItem(
                    selectedIndex = viewModel.olderThanTypeIndex,
                    onSelect = { viewModel.olderThanTypeIndex = it },
                )
                neverDeleteFromItems(
                    input = viewModel.deleteFrom,
                    onInputChange = { viewModel.deleteFrom = it },
                    onPaste = {
                        scope.launch {
                            val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                            if (!viewModel.pasteNeverDeleteFrom(text)) {
                                Toast.makeText(context, invalidKeyMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAdd = {
                        if (!viewModel.addNeverDeleteFrom()) {
                            Toast.makeText(context, invalidKeyMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    pubkeys = viewModel.neverDeleteFrom,
                    onDelete = viewModel::removeNeverDeleteFrom,
                )
                preservedKindsItems(
                    input = viewModel.preservedKindInput,
                    onInputChange = { viewModel.preservedKindInput = it },
                    onPaste = {
                        scope.launch {
                            val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                            if (!viewModel.pastePreservedKind(text)) {
                                Toast.makeText(context, invalidKindMsg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAdd = {
                        if (!viewModel.addPreservedKind()) {
                            Toast.makeText(context, invalidKindMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    kinds = viewModel.preservedKindsFromDeletion,
                    onDelete = viewModel::removePreservedKind,
                )
            }

            SettingsApplyBar(
                enabled = !viewModel.isLoading,
                onApply = { viewModel.applyChanges(onApplyChanges) },
            )
        }
    }
}

private fun LazyListScope.olderThanItem(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    item {
        val deleteItems = persistentListOf(
            TitleExplainer(stringResource(OlderThanType.NEVER.resourceId)),
            TitleExplainer(stringResource(OlderThanType.DAY.resourceId)),
            TitleExplainer(stringResource(OlderThanType.WEEK.resourceId)),
            TitleExplainer(stringResource(OlderThanType.MONTH.resourceId)),
            TitleExplainer(stringResource(OlderThanType.YEAR.resourceId)),
        )
        SettingsRow(
            name = R.string.delete_events_older_than,
            description = R.string.delete_events_older_than_description,
            selectedItems = deleteItems,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
    }
}

private fun LazyListScope.neverDeleteFromItems(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    pubkeys: Set<String>,
    onDelete: (String) -> Unit,
) {
    stickyHeader {
        SectionHeader(stringResource(R.string.never_delete_from))
    }
    item {
        PubkeyInputRow(
            value = input,
            onValueChange = onInputChange,
            onPaste = onPaste,
            onAdd = onAdd,
        )
    }
    items(pubkeys.toList()) { pubkey ->
        PubkeyListItem(
            text = pubkey.toShortenHex(),
            onDelete = { onDelete(pubkey) },
        )
    }
}

private fun LazyListScope.preservedKindsItems(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    kinds: Set<Int>,
    onDelete: (Int) -> Unit,
) {
    stickyHeader {
        SectionHeader(stringResource(R.string.preserved_kinds_from_deletion))
    }
    item {
        EmptyListHint(stringResource(R.string.preserved_kinds_from_deletion_description))
    }
    item {
        PubkeyInputRow(
            value = input,
            onValueChange = onInputChange,
            onPaste = onPaste,
            onAdd = onAdd,
        )
    }
    items(kinds.toList()) { k ->
        PubkeyListItem(
            text = k.toString(),
            onDelete = { onDelete(k) },
        )
    }
}
