@file:OptIn(ExperimentalFoundationApi::class)

package com.greenart7c3.citrine.ui.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission

private enum class ImportTarget { SIGNED_BY, REFERS_TO }

private data class ImportSession(val pubkey: String, val packageName: String)

@Composable
fun AccessControlSettingsScreen(
    onApplyChanges: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccessControlSettingsViewModel = viewModel(factory = AccessControlSettingsViewModel.factory(LocalContext.current.applicationContext)),
) {
    val context = LocalContext.current
    val paste = rememberClipboardPaste()
    val invalidKeyMsg = stringResource(R.string.invalid_key)
    val invalidKindMsg = stringResource(R.string.invalid_kind)

    Surface(modifier) {
        var importTarget by remember { mutableStateOf<ImportTarget?>(null) }
        var importSession by remember { mutableStateOf<ImportSession?>(null) }

        val loginForImport = rememberImportSignerLogin(
            onSession = { importSession = it },
            onCancel = { importTarget = null },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                accessControlItems(
                    allowedPubKeys = viewModel.allowedPubKeys,
                    allowedTaggedPubKeys = viewModel.allowedTaggedPubKeys,
                    allowedKinds = viewModel.allowedKinds,
                    rejectedKinds = viewModel.rejectedKinds,
                    paste = paste,
                    onInvalidKey = { Toast.makeText(context, invalidKeyMsg, Toast.LENGTH_SHORT).show() },
                    onInvalidKind = { Toast.makeText(context, invalidKindMsg, Toast.LENGTH_SHORT).show() },
                    onImport = { target ->
                        importTarget = target
                        loginForImport()
                    },
                )
            }

            SettingsApplyBar(enabled = !viewModel.isLoading, onApply = { viewModel.applyChanges(onApplyChanges) })
        }

        importSession?.let { session ->
            val target = importTarget
            if (target != null) {
                ImportFromListsDialog(
                    signerPubkey = session.pubkey,
                    signerPackage = session.packageName,
                    onDismiss = {
                        importSession = null
                        importTarget = null
                    },
                    onConfirm = { selected ->
                        if (target == ImportTarget.SIGNED_BY) {
                            viewModel.allowedPubKeys.addAll(selected)
                        } else {
                            viewModel.allowedTaggedPubKeys.addAll(selected)
                        }
                        importSession = null
                        importTarget = null
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberImportSignerLogin(
    onSession: (ImportSession) -> Unit,
    onCancel: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noSignerMsg = stringResource(R.string.no_external_signer_installed)

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
            onCancel()
        } else {
            result.data?.let { data ->
                try {
                    val key = data.getStringExtra("signature") ?: ""
                    val packageName = data.getStringExtra("package") ?: ""
                    val returnedKey = if (key.startsWith("npub")) {
                        when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                            is NPub -> parsed.hex
                            else -> ""
                        }
                    } else {
                        key
                    }
                    if (returnedKey.isNotBlank() && packageName.isNotBlank()) {
                        onSession(ImportSession(returnedKey, packageName))
                    } else {
                        onCancel()
                    }
                } catch (e: Exception) {
                    Log.d(Citrine.TAG, e.message ?: "", e)
                    onCancel()
                }
            }
        }
    }

    return {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
            intent.putExtra("type", "get_public_key")
            val permissions = listOf(
                Permission(CommandType.NIP04_DECRYPT),
                Permission(CommandType.NIP44_DECRYPT),
            ).joinToString(",") { it.toJson() }
            intent.putExtra("permissions", "[$permissions]")
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            importLauncher.launch(intent)
        } catch (e: Exception) {
            Log.d(Citrine.TAG, e.message ?: "", e)
            Toast.makeText(context, noSignerMsg, Toast.LENGTH_SHORT).show()
            onCancel()
        }
    }
}

private fun LazyListScope.accessControlItems(
    allowedPubKeys: EditableSet<String>,
    allowedTaggedPubKeys: EditableSet<String>,
    allowedKinds: EditableSet<Int>,
    rejectedKinds: EditableSet<Int>,
    paste: (onText: (String) -> Unit) -> Unit,
    onInvalidKey: () -> Unit,
    onInvalidKind: () -> Unit,
    onImport: (ImportTarget) -> Unit,
) {
    editableSetItems(
        header = R.string.accept_events_signed_by,
        emptyHint = R.string.allow_all_pubkeys_hint,
        set = allowedPubKeys,
        paste = paste,
        onInvalid = onInvalidKey,
        label = { it.toShortenHex() },
        onImport = { onImport(ImportTarget.SIGNED_BY) },
    )
    editableSetItems(
        header = R.string.accept_events_that_refer_to,
        emptyHint = R.string.allow_all_tagged_pubkeys_hint,
        set = allowedTaggedPubKeys,
        paste = paste,
        onInvalid = onInvalidKey,
        label = { it.toShortenHex() },
        onImport = { onImport(ImportTarget.REFERS_TO) },
    )
    editableSetItems(
        header = R.string.allowed_kinds,
        emptyHint = R.string.allow_all_kinds_hint,
        set = allowedKinds,
        paste = paste,
        onInvalid = onInvalidKind,
        label = { it.toString() },
    )
    editableSetItems(
        header = R.string.rejected_kinds,
        emptyHint = R.string.reject_no_kinds_hint,
        set = rejectedKinds,
        paste = paste,
        onInvalid = onInvalidKind,
        label = { it.toString() },
    )
}

private fun <T> LazyListScope.editableSetItems(
    @StringRes header: Int,
    @StringRes emptyHint: Int,
    set: EditableSet<T>,
    paste: (onText: (String) -> Unit) -> Unit,
    onInvalid: () -> Unit,
    label: (T) -> String,
    onImport: (() -> Unit)? = null,
) {
    stickyHeader {
        SectionHeader(stringResource(header))
    }
    item {
        PubkeyInputRow(
            value = set.input,
            onValueChange = { set.input = it },
            onPaste = { paste { if (!set.paste(it)) onInvalid() } },
            onAdd = { if (!set.add()) onInvalid() },
        )
    }
    if (onImport != null) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onImport) {
                    Text(stringResource(R.string.import_from_lists))
                }
            }
        }
    }
    if (set.values.isEmpty()) {
        item {
            EmptyListHint(stringResource(emptyHint))
        }
    }
    items(set.values.toList()) { entry ->
        PubkeyListItem(
            text = label(entry),
            onDelete = { set.remove(entry) },
        )
    }
}
