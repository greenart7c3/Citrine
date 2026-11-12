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

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen report review: the user sees and can trim what is sent, then either signs it with
 * Amber (the developer can reply to their npub) or sends it from a throwaway key.
 */
@Composable
fun CrashReportScreen() {
    val context = LocalContext.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noExternalSignerMsg = stringResource(R.string.no_external_signer_installed)
    val sentMsg = stringResource(R.string.crash_report_sent)
    val failedMsg = stringResource(R.string.crash_report_send_failed)

    var reportText by remember { mutableStateOf("") }
    var showScreen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Kept alive while the send is in flight: it holds the launcher Amber answers through.
    var signer by remember { mutableStateOf<NostrSignerExternal?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val stack = Citrine.instance.crashReportCache.loadAndDelete()
            // ANRs, low-memory kills and native crashes never reach the uncaught-exception
            // handler, so the exit records are the only trace of them the user can send.
            val exits = ExitReasons.pending(Citrine.instance)
            val joined = listOfNotNull(stack?.takeIf { it.isNotBlank() }, exits?.takeIf { it.isNotBlank() })
                .joinToString("\n\n")
            if (joined.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    reportText = joined
                    showScreen = true
                }
            }
        }
    }

    fun forgetReport() {
        ExitReasons.clearPending(Citrine.instance)
        showScreen = false
    }

    fun sendReport(with: NostrSigner) {
        val snapshot = reportText
        sending = true
        Citrine.instance.applicationScope.launch(Dispatchers.IO) {
            val sent = CrashReportSender.send(snapshot, with)
            withContext(Dispatchers.Main) {
                sending = false
                Toast.makeText(context, if (sent) sentMsg else failedMsg, Toast.LENGTH_LONG).show()
                // On failure the cache file is already gone, so the screen keeps the only copy.
                if (sent) forgetReport()
            }
        }
    }

    // Responses to sign requests Amber sent back while the report was being built.
    val respondLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
            } else {
                result.data?.let { data ->
                    Citrine.instance.applicationScope.launch(Dispatchers.IO) {
                        signer?.newResponse(data)
                    }
                }
            }
        },
    )

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
            } else {
                val pubkey = result.data?.getStringExtra("signature").orEmpty()
                val packageName = result.data?.getStringExtra("package").orEmpty()

                val hexKey = if (pubkey.startsWith("npub")) {
                    (Nip19Parser.uriToRoute(pubkey)?.entity as? NPub)?.hex.orEmpty()
                } else {
                    pubkey
                }

                if (hexKey.isBlank() || packageName.isBlank()) {
                    Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
                } else {
                    val externalSigner = NostrSignerExternal(
                        hexKey,
                        packageName,
                        Citrine.instance.contentResolver,
                    )
                    // Covers the case where the user did not tick "remember" in Amber, so every
                    // seal and gift wrap needs its own confirmation there.
                    externalSigner.registerForegroundLauncher { intent ->
                        try {
                            respondLauncher.launch(intent)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(Citrine.TAG, "Could not open the signer app", e)
                        }
                    }
                    signer = externalSigner
                    sendReport(externalSigner)
                }
            }
        },
    )

    fun signWithAmber() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
            intent.putExtra("type", "get_public_key")
            // One Amber prompt covers the whole NIP-17 build: the chat message, the seal and the
            // NIP-44 encryption of both.
            val permissions = listOf(
                Permission(CommandType.SIGN_EVENT, 14),
                Permission(CommandType.SIGN_EVENT, 13),
                Permission(CommandType.NIP44_ENCRYPT),
            )
            intent.putExtra("permissions", permissions.joinToString(",", "[", "]") { it.toJson() })
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            loginLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(Citrine.TAG, "Could not open the signer app", e)
            Toast.makeText(context, noExternalSignerMsg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "Could not open the signer app", e)
            Toast.makeText(context, noExternalSignerMsg, Toast.LENGTH_LONG).show()
        }
    }

    if (!showScreen) return

    BackHandler(enabled = !sending) { forgetReport() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.crashreport_found),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.crash_report_send_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = reportText,
                onValueChange = { reportText = it },
                enabled = !sending,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            if (sending) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = { signWithAmber() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending && reportText.isNotBlank(),
            ) {
                Text(stringResource(R.string.crash_report_sign_with_amber))
            }
            OutlinedButton(
                onClick = { sendReport(CrashReportSender.anonymousSigner()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending && reportText.isNotBlank(),
            ) {
                Text(stringResource(R.string.crash_report_send_anonymously))
            }
            TextButton(
                onClick = { forgetReport() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !sending,
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
