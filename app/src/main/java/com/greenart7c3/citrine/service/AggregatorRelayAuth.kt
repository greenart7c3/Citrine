package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import com.greenart7c3.citrine.data.RelayClient
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.relay.client.auth.RelayAuthenticator
import com.vitorpamplona.quartz.nip42RelayAuth.RelayAuthEvent
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "RelayAggregator"

internal class AggregatorRelayAuth {
    @Volatile private var authenticator: RelayAuthenticator? = null

    @Volatile var relayAuthSigner: NostrSignerExternal? = null
        private set

    @Volatile private var installedAuthIdentity: Pair<String, String>? = null

    private val lastSignedAuthByRelay: ConcurrentHashMap<String, Pair<String, RelayAuthEvent>> = ConcurrentHashMap()

    @Volatile var activityLauncher: ((Intent) -> Unit)? = null

    fun destroy() {
        dropInstalledAuthenticator()
        lastSignedAuthByRelay.clear()
    }

    private fun dropInstalledAuthenticator() {
        authenticator?.destroy()
        authenticator = null
        relayAuthSigner = null
        installedAuthIdentity = null
    }

    private fun isInstalledFor(signerIdentity: Pair<String, String>?): Boolean = authenticator != null && installedAuthIdentity == signerIdentity

    fun installAuthenticatorIfNeeded(scope: CoroutineScope, context: Context, relayClient: RelayClient) {
        val pubkey = Settings.aggregatorSignerPubkey
        val pkg = Settings.aggregatorSignerPackageName

        val desired = if (pubkey.isBlank() || pkg.isBlank()) null else (pubkey to pkg)
        if (isInstalledFor(desired)) return

        dropInstalledAuthenticator()

        if (desired == null) return
        val signer = NostrSignerExternal(pubkey, pkg, context.contentResolver)
        signer.registerForegroundLauncher { intent -> launchSignerThroughActivity(intent, pkg) }
        relayAuthSigner = signer
        installedAuthIdentity = desired
        lastSignedAuthByRelay.clear()
        Log.d(TAG, "Installing RelayAuthenticator for $pubkey via $pkg")
        authenticator = RelayAuthenticator(relayClient.client, scope) { relay, template, _ ->
            val relayUrl = relay.url
            val challenge = template.tags.firstOrNull { it.size > 1 && it[0] == "challenge" }?.get(1)
            if (challenge != null) {
                val cached = lastSignedAuthByRelay[relayUrl]
                if (cached != null && cached.first == challenge) {
                    Log.d(TAG, "Reusing cached AUTH for $relayUrl (challenge unchanged)")
                    return@RelayAuthenticator listOf(cached.second)
                }
            }
            try {
                val signed = signer.sign(template)
                if (challenge != null) {
                    lastSignedAuthByRelay[relayUrl] = challenge to signed
                }
                listOf(signed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sign AUTH challenge", e)
                emptyList()
            }
        }
    }

    /**
     * Amber identifies the caller through `Intent.callingPackage`, which only the
     * ActivityResultLauncher MainActivity registers populates: a bare startActivity() leaves
     * it null and Amber rejects the request. Without this foreground path, sign() throws
     * `RunningOnBackgroundWithoutAutomaticPermissionException` the first time Amber needs
     * manual approval. When MainActivity isn't alive this path can't run; the user must open
     * Citrine once so onCreate registers the launcher, and from then on the ContentResolver
     * background path also works thanks to Amber's stored auto-approve.
     */
    @Suppress("NoComments")
    private fun launchSignerThroughActivity(intent: Intent, pkg: String) {
        val launcher = activityLauncher
        if (launcher == null) {
            Log.w(TAG, "No Activity launcher registered; open Citrine once to authorize the signer")
            return
        }
        try {
            intent.`package` = pkg
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            launcher(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch signer intent via Activity launcher", e)
        }
    }
}
