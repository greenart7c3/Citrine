@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui.settings

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SettingsRow
import com.greenart7c3.citrine.ui.components.TitleExplainer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RetentionSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current

    Surface(modifier) {
        var neverDeleteFrom by remember { mutableStateOf(Settings.neverDeleteFrom) }
        var preservedKindsFromDeletion by remember { mutableStateOf(Settings.preservedKindsFromDeletion) }
        var preservedKindInput by remember { mutableStateOf(TextFieldValue("")) }
        var deleteFrom by remember { mutableStateOf(TextFieldValue("")) }
        var olderThanTypeIndex by remember {
            mutableIntStateOf(
                OlderThanType.entries.toTypedArray()
                    .indexOfFirst { it.name == Settings.deleteEventsOlderThan.toString() },
            )
        }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.neverDeleteFrom = neverDeleteFrom
                Settings.preservedKindsFromDeletion = preservedKindsFromDeletion
                Settings.deleteEventsOlderThan = when (olderThanTypeIndex) {
                    OlderThanType.NEVER.screenCode -> OlderThan.NEVER
                    OlderThanType.DAY.screenCode -> OlderThan.DAY
                    OlderThanType.WEEK.screenCode -> OlderThan.WEEK
                    OlderThanType.MONTH.screenCode -> OlderThan.MONTH
                    OlderThanType.YEAR.screenCode -> OlderThan.YEAR
                    else -> OlderThan.NEVER
                }
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
                        selectedIndex = olderThanTypeIndex,
                    ) {
                        olderThanTypeIndex = it
                    }
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.never_delete_from))
                }
                item {
                    PubkeyInputRow(
                        value = deleteFrom,
                        onValueChange = { deleteFrom = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                deleteFrom = TextFieldValue(text)
                                val key = text.toNostrKey()
                                if (key == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                neverDeleteFrom = neverDeleteFrom + key
                                deleteFrom = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val key = deleteFrom.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            neverDeleteFrom = neverDeleteFrom + key
                            deleteFrom = TextFieldValue("")
                        },
                    )
                }
                items(neverDeleteFrom.toList()) { pubkey ->
                    PubkeyListItem(
                        text = pubkey.toShortenHex(),
                        onDelete = { neverDeleteFrom = neverDeleteFrom - pubkey },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.preserved_kinds_from_deletion))
                }
                item {
                    EmptyListHint(stringResource(R.string.preserved_kinds_from_deletion_description))
                }
                item {
                    PubkeyInputRow(
                        value = preservedKindInput,
                        onValueChange = { preservedKindInput = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                preservedKindInput = TextFieldValue(text)
                                val k = text.toIntOrNull()
                                if (k == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                preservedKindsFromDeletion = preservedKindsFromDeletion + k
                                preservedKindInput = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val k = preservedKindInput.text.toIntOrNull()
                            if (k == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            preservedKindsFromDeletion = preservedKindsFromDeletion + k
                            preservedKindInput = TextFieldValue("")
                        },
                    )
                }
                items(preservedKindsFromDeletion.toList()) { k ->
                    PubkeyListItem(
                        text = k.toString(),
                        onDelete = { preservedKindsFromDeletion = preservedKindsFromDeletion - k },
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
