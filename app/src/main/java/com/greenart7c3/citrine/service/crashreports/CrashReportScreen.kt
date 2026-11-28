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
import android.content.Context
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
import com.greenart7c3.citrine.ui.LocalAppScope
import com.greenart7c3.citrine.ui.LocalCrashReportCache
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
fun CrashReportScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val appScope = LocalAppScope.current
    val crashReportCache = LocalCrashReportCache.current
    val sentMsg = stringResource(R.string.crash_report_sent)
    val failedMsg = stringResource(R.string.crash_report_send_failed)

    var reportText by remember { mutableStateOf("") }
    var showScreen by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    // Kept alive while the send is in flight: it holds the launcher Amber answers through.
    var signer by remember { mutableStateOf<NostrSignerExternal?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val joined = loadReport(crashReportCache, appContext)
            if (joined.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    reportText = joined
                    showScreen = true
                }
            }
        }
    }

    fun forgetReport() {
        ExitReasons.clearPending(appContext)
        showScreen = false
    }

    fun sendReport(with: NostrSigner) {
        val snapshot = reportText
        sending = true
        appScope.launch(Dispatchers.IO) {
            val sent = CrashReportSender.send(snapshot, with)
            withContext(Dispatchers.Main) {
                sending = false
                Toast.makeText(context, if (sent) sentMsg else failedMsg, Toast.LENGTH_LONG).show()
                // On failure the cache file is already gone, so the screen keeps the only copy.
                if (sent) forgetReport()
            }
        }
    }

    val signWithAmber = rememberAmberSignIn(
        currentSigner = { signer },
        onSignerReady = { externalSigner ->
            signer = externalSigner
            sendReport(externalSigner)
        },
    )

    if (!showScreen) return

    BackHandler(enabled = !sending) { forgetReport() }

    CrashReportContent(
        reportText = reportText,
        onReportTextChange = { reportText = it },
        sending = sending,
        onSignWithAmber = signWithAmber,
        onSendAnonymously = { sendReport(CrashReportSender.anonymousSigner()) },
        onCancel = { forgetReport() },
        modifier = modifier,
    )
}

@Composable
private fun rememberAmberSignIn(
    currentSigner: () -> NostrSignerExternal?,
    onSignerReady: (NostrSignerExternal) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val appScope = LocalAppScope.current
    val signRequestRejectedMsg = stringResource(R.string.sign_request_rejected)
    val noExternalSignerMsg = stringResource(R.string.no_external_signer_installed)

    // Responses to sign requests Amber sent back while the report was being built.
    val respondLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
            } else {
                result.data?.let { data ->
                    appScope.launch(Dispatchers.IO) {
                        currentSigner()?.newResponse(data)
                    }
                }
            }
        },
    )

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { result ->
            val externalSigner = result.takeIf { it.resultCode == RESULT_OK }
                ?.let { amberSigner(it.data, appContext) }
            if (externalSigner == null) {
                Toast.makeText(context, signRequestRejectedMsg, Toast.LENGTH_SHORT).show()
            } else {
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
                onSignerReady(externalSigner)
            }
        },
    )

    return {
        try {
            loginLauncher.launch(amberLoginIntent())
        } catch (e: ActivityNotFoundException) {
            Log.e(Citrine.TAG, "Could not open the signer app", e)
            Toast.makeText(context, noExternalSignerMsg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "Could not open the signer app", e)
            Toast.makeText(context, noExternalSignerMsg, Toast.LENGTH_LONG).show()
        }
    }
}

private suspend fun loadReport(crashReportCache: CrashReportCache, appContext: Context): String {
    val stack = crashReportCache.loadAndDelete()
    // ANRs and native crashes never reach the uncaught-exception handler, so the exit
    // records are the only trace of them the user can send. Run detect() here too so the
    // crash screen does not race ahead of Citrine.onCreate()'s background detection.
    ExitReasons.detect(appContext)
    val exits = ExitReasons.pending(appContext)
    return listOfNotNull(stack?.takeIf { it.isNotBlank() }, exits?.takeIf { it.isNotBlank() })
        .joinToString("\n\n")
}

private fun amberSigner(data: Intent?, appContext: Context): NostrSignerExternal? {
    val pubkey = data?.getStringExtra("signature").orEmpty()
    val packageName = data?.getStringExtra("package").orEmpty()

    val hexKey = if (pubkey.startsWith("npub")) {
        (Nip19Parser.uriToRoute(pubkey)?.entity as? NPub)?.hex.orEmpty()
    } else {
        pubkey
    }

    if (hexKey.isBlank() || packageName.isBlank()) return null
    return NostrSignerExternal(
        hexKey,
        packageName,
        appContext.contentResolver,
    )
}

private fun amberLoginIntent(): Intent {
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
    return intent
}

@Composable
private fun CrashReportContent(
    reportText: String,
    onReportTextChange: (String) -> Unit,
    sending: Boolean,
    onSignWithAmber: () -> Unit,
    onSendAnonymously: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
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
                onValueChange = onReportTextChange,
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
                onClick = onSignWithAmber,
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending && reportText.isNotBlank(),
            ) {
                Text(stringResource(R.string.crash_report_sign_with_amber))
            }
            OutlinedButton(
                onClick = onSendAnonymously,
                modifier = Modifier.fillMaxWidth(),
                enabled = !sending && reportText.isNotBlank(),
            ) {
                Text(stringResource(R.string.crash_report_send_anonymously))
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enabled = !sending,
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
