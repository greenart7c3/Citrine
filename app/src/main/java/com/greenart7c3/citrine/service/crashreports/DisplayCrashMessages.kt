/**
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.greenart7c3.citrine.service.crashreports

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DisplayCrashMessages() {
    val stackTrace = remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboard.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            stackTrace.value = Citrine.instance.crashReportCache.loadAndDelete()
        }
    }

    stackTrace.value?.let { stack ->
        CrashReportAlertDialog(
            text = stringResource(R.string.copy_crash_report_to_clipboard),
            onDismiss = { stackTrace.value = null },
            onConfirm = {
                Citrine.instance.applicationScope.launch(Dispatchers.Main) {
                    try {
                        clipboardManager.setClipEntry(
                            ClipEntry(
                                ClipData.newPlainText("", stack),
                            ),
                        )
                        val intent = Intent(Intent.ACTION_VIEW)
                        val npub = Hex.decode("7579076d9aff0a4cfdefa7e2045f2486c7e5d8bc63bfc6b45397233e1bbfcb19").toNpub()
                        intent.data = "nostr:$npub".toUri()
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        Citrine.instance.startActivity(intent)
                        stackTrace.value = null
                    } catch (_: Exception) {
                        stackTrace.value = null
                    }
                }
            },
        )
    }
}

@Composable
fun CrashReportAlertDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    text: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crashreport_found)) },
        text = {
            SelectionContainer {
                Text(text)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Done,
                        contentDescription = stringResource(R.string.crashreport_found_send),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.crashreport_found_send))
                }
            }
        },
    )
}
