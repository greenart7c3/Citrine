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
    // RelayAuthenticator listens for NIP-42 AUTH challenges from upstream relays and signs
    // a kind-22242 event using the external signer. Held so the start/stop lifecycle ties
    // the authenticator's lifespan to the aggregator's scope; it stops listening on cancel.
    @Volatile private var authenticator: RelayAuthenticator? = null

    // External signer used by the authenticator. Held so [deliverSignerResponse] can route
    // an Intent result received by MainActivity into the same instance whose suspended
    // sign() call is awaiting it.
    @Volatile var relayAuthSigner: NostrSignerExternal? = null
        private set

    // Identity (pubkey + packageName) of the signer the currently-installed authenticator
    // was built for. Used by installAuthenticatorIfNeeded to skip a rebuild when the
    // signer hasn't changed — every onConfigChanged call would otherwise tear down and
    // recreate the authenticator, briefly leaving zero listeners and discarding any
    // in-flight AUTH state.
    @Volatile private var installedAuthIdentity: Pair<String, String>? = null

    // Cache of (relay → last challenge string, signed AUTH event) so a relay re-sending
    // the same challenge — common on reconnect, and what was driving the duplicate Amber
    // prompts — returns the previously-signed event without re-invoking the signer.
    private val signedAuthCache: ConcurrentHashMap<String, Pair<String, RelayAuthEvent>> = ConcurrentHashMap()

    // Activity-scoped Intent launcher set by MainActivity. Uses registerForActivityResult
    // under the hood, which preserves the caller's identity on the intent — Amber relies
    // on that callingPackage to identify which app is asking, and a plain Application-
    // context startActivity() makes it null.
    @Volatile var activityLauncher: ((Intent) -> Unit)? = null

    fun destroy() {
        authenticator?.destroy()
        authenticator = null
        relayAuthSigner = null
        installedAuthIdentity = null
        signedAuthCache.clear()
    }

    /**
     * Construct a [RelayAuthenticator] bound to [scope] when a signer identity is configured.
     * Re-entrant: drops a previously installed authenticator before installing a new one.
     * No-op when the signer pubkey/packageName is missing — relays that send AUTH challenges
     * will simply not get a response, which matches the prior behavior.
     *
     * The signer is configured with both the ContentResolver (background signing path, used
     * when Amber has stored an auto-approve for kind 22242) and a foreground intent launcher
     * (fallback that pops the Amber UI from the application context when no auto-approve is
     * available). Without the foreground launcher, sign() throws
     * `RunningOnBackgroundWithoutAutomaticPermissionException` the first time Amber needs
     * manual approval.
     */
    fun installAuthenticatorIfNeeded(scope: CoroutineScope, context: Context, relayClient: RelayClient) {
        val pubkey = Settings.aggregatorSignerPubkey
        val pkg = Settings.aggregatorSignerPackageName

        // Skip the tear-down/rebuild if the signer identity matches what we already have
        // installed. onConfigChanged fires for every settings tweak (kind add, refresh
        // interval, etc.); without this guard each one would briefly leave the relay
        // unprotected and discard in-flight AUTH state.
        val desired = if (pubkey.isBlank() || pkg.isBlank()) null else (pubkey to pkg)
        if (authenticator != null && installedAuthIdentity == desired) return

        // Always tear down the previous authenticator before installing a new one — its
        // internal client listener stays registered with NostrClient until destroy() runs,
        // and a second authenticator with the same listener would answer the same AUTH
        // challenge twice.
        authenticator?.destroy()
        authenticator = null
        relayAuthSigner = null
        installedAuthIdentity = null

        if (desired == null) return
        val signer = NostrSignerExternal(pubkey, pkg, context.contentResolver)
        // Lets the signer fall back to Amber's UI when the background ContentResolver path
        // can't auto-approve. The launcher routes through MainActivity's
        // ActivityResultLauncher (set via [registerActivityLauncher]), which preserves
        // callingPackage on the intent and feeds Amber's reply back into this signer via
        // [deliverSignerResponse] so the suspended sign() resumes.
        signer.registerForegroundLauncher { intent ->
            // Must go through the ActivityResultLauncher MainActivity registered with us —
            // that's what populates Intent.callingPackage so Amber can authenticate the
            // caller. A bare activity.startActivity() leaves callingPackage null and
            // Amber rejects. When MainActivity isn't alive, the foreground path can't
            // run; the user must open Citrine once so onCreate registers the launcher,
            // and from then on the ContentResolver background path also works thanks to
            // Amber's stored auto-approve.
            val launcher = activityLauncher
            if (launcher == null) {
                Log.w(TAG, "No Activity launcher registered; open Citrine once to authorize the signer")
                return@registerForegroundLauncher
            }
            try {
                intent.`package` = pkg
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launcher(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch signer intent via Activity launcher", e)
            }
        }
        relayAuthSigner = signer
        installedAuthIdentity = desired
        signedAuthCache.clear()
        Log.d(TAG, "Installing RelayAuthenticator for $pubkey via $pkg")
        authenticator = RelayAuthenticator(relayClient.client, scope) { relay, template, _ ->
            val relayUrl = relay.url
            val challenge = template.tags.firstOrNull { it.size > 1 && it[0] == "challenge" }?.get(1)
            if (challenge != null) {
                val cached = signedAuthCache[relayUrl]
                if (cached != null && cached.first == challenge) {
                    Log.d(TAG, "Reusing cached AUTH for $relayUrl (challenge unchanged)")
                    return@RelayAuthenticator listOf(cached.second)
                }
            }
            try {
                val signed = signer.sign(template)
                if (challenge != null) {
                    signedAuthCache[relayUrl] = challenge to signed
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
}
