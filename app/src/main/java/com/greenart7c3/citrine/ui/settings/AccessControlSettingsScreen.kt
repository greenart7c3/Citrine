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
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.ui.components.PubkeyInputRow
import com.greenart7c3.citrine.ui.components.PubkeyListItem
import com.greenart7c3.citrine.ui.components.SwitchSettingRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AccessControlSettingsScreen(
    modifier: Modifier = Modifier,
    onApplyChanges: () -> Unit,
) {
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current

    Surface(modifier) {
        var useAuth by remember { mutableStateOf(Settings.authEnabled) }
        var allowedPubKeys by remember { mutableStateOf(Settings.allowedPubKeys) }
        var allowedTaggedPubKeys by remember { mutableStateOf(Settings.allowedTaggedPubKeys) }
        var allowedKinds by remember { mutableStateOf(Settings.allowedKinds) }
        var rejectedKinds by remember { mutableStateOf(Settings.rejectedKinds) }

        var signedBy by remember { mutableStateOf(TextFieldValue("")) }
        var referredBy by remember { mutableStateOf(TextFieldValue("")) }
        var kind by remember { mutableStateOf(TextFieldValue("")) }
        var rejectedKind by remember { mutableStateOf(TextFieldValue("")) }

        val applyChanges = {
            scope.launch(Dispatchers.IO) {
                isLoading = true
                Settings.authEnabled = useAuth
                Settings.allowedPubKeys = allowedPubKeys
                Settings.allowedTaggedPubKeys = allowedTaggedPubKeys
                Settings.allowedKinds = allowedKinds
                Settings.rejectedKinds = rejectedKinds
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
                        title = stringResource(R.string.enable_disable_auth),
                        description = stringResource(R.string.enable_disable_auth_description),
                        checked = useAuth,
                        onCheckedChange = { useAuth = it },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.accept_events_signed_by))
                }
                item {
                    PubkeyInputRow(
                        value = signedBy,
                        onValueChange = { signedBy = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                signedBy = TextFieldValue(text)
                                val key = text.toNostrKey()
                                if (key == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                allowedPubKeys = allowedPubKeys + key
                                signedBy = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val key = signedBy.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            allowedPubKeys = allowedPubKeys + key
                            signedBy = TextFieldValue("")
                        },
                    )
                }
                if (allowedPubKeys.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.allow_all_pubkeys_hint))
                    }
                }
                items(allowedPubKeys.toList()) { pubkey ->
                    PubkeyListItem(
                        text = pubkey.toShortenHex(),
                        onDelete = { allowedPubKeys = allowedPubKeys - pubkey },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.accept_events_that_refer_to))
                }
                item {
                    PubkeyInputRow(
                        value = referredBy,
                        onValueChange = { referredBy = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                referredBy = TextFieldValue(text)
                                val key = text.toNostrKey()
                                if (key == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                allowedTaggedPubKeys = allowedTaggedPubKeys + key
                                referredBy = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val key = referredBy.text.toNostrKey()
                            if (key == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_key), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            allowedTaggedPubKeys = allowedTaggedPubKeys + key
                            referredBy = TextFieldValue("")
                        },
                    )
                }
                if (allowedTaggedPubKeys.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.allow_all_tagged_pubkeys_hint))
                    }
                }
                items(allowedTaggedPubKeys.toList()) { pubkey ->
                    PubkeyListItem(
                        text = pubkey.toShortenHex(),
                        onDelete = { allowedTaggedPubKeys = allowedTaggedPubKeys - pubkey },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.allowed_kinds))
                }
                item {
                    PubkeyInputRow(
                        value = kind,
                        onValueChange = { kind = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                kind = TextFieldValue(text)
                                val k = text.toIntOrNull()
                                if (k == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                allowedKinds = allowedKinds + k
                                kind = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val k = kind.text.toIntOrNull()
                            if (k == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            allowedKinds = allowedKinds + k
                            kind = TextFieldValue("")
                        },
                    )
                }
                if (allowedKinds.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.allow_all_kinds_hint))
                    }
                }
                items(allowedKinds.toList()) { k ->
                    PubkeyListItem(
                        text = k.toString(),
                        onDelete = { allowedKinds = allowedKinds - k },
                    )
                }

                stickyHeader {
                    SectionHeader(stringResource(R.string.rejected_kinds))
                }
                item {
                    PubkeyInputRow(
                        value = rejectedKind,
                        onValueChange = { rejectedKind = it },
                        onPaste = {
                            scope.launch {
                                val text = clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString() ?: return@launch
                                rejectedKind = TextFieldValue(text)
                                val k = text.toIntOrNull()
                                if (k == null) {
                                    Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                rejectedKinds = rejectedKinds + k
                                rejectedKind = TextFieldValue("")
                            }
                        },
                        onAdd = {
                            val k = rejectedKind.text.toIntOrNull()
                            if (k == null) {
                                Toast.makeText(context, context.getString(R.string.invalid_kind), Toast.LENGTH_SHORT).show()
                                return@PubkeyInputRow
                            }
                            rejectedKinds = rejectedKinds + k
                            rejectedKind = TextFieldValue("")
                        },
                    )
                }
                if (rejectedKinds.isEmpty()) {
                    item {
                        EmptyListHint(stringResource(R.string.reject_no_kinds_hint))
                    }
                }
                items(rejectedKinds.toList()) { k ->
                    PubkeyListItem(
                        text = k.toString(),
                        onDelete = { rejectedKinds = rejectedKinds - k },
                    )
                }
            }

            SettingsApplyBar(enabled = !isLoading, onApply = applyChanges)
        }
    }
}
