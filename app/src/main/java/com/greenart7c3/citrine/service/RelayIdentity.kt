package com.greenart7c3.citrine.service

import android.content.Intent
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal

/**
 * The relay's NIP-29 signing identity: the relay owner's key, exercised through the
 * Amber (NIP-55) external signer configured by the "login with Amber" flow in relay
 * info settings. Group metadata events (kinds 39000-39003) and relay-issued membership
 * changes (9000/9001) are signed with it. There is no local private key — with no owner
 * configured, [signer] returns null and NIP-29 management stays disabled
 * (see [Settings.nip29Enabled]).
 */
object RelayIdentity {
    @Volatile
    private var cachedSigner: NostrSignerExternal? = null

    // Identity (pubkey + packageName) the cached signer was built for, so a settings
    // change rebuilds the signer instead of signing with a stale identity.
    @Volatile
    private var cachedIdentity: Pair<String, String>? = null

    // Activity-scoped Intent launcher set by MainActivity. Uses registerForActivityResult
    // under the hood, which preserves the caller's identity on the intent — Amber relies
    // on that callingPackage to identify which app is asking, and a plain Application-
    // context startActivity() makes it null.
    @Volatile
    private var activityLauncher: ((Intent) -> Unit)? = null

    fun registerActivityLauncher(launcher: (Intent) -> Unit) {
        activityLauncher = launcher
    }

    fun unregisterActivityLauncher() {
        activityLauncher = null
    }

    /** Routes an Intent result received by MainActivity into the suspended sign() call. */
    fun deliverSignerResponse(data: Intent) {
        cachedSigner?.newResponse(data)
    }

    fun pubKeyHex(): String = Settings.ownerPubkey

    @Synchronized
    fun signer(): NostrSignerExternal? {
        val pubkey = Settings.ownerPubkey
        val pkg = Settings.relaySignerPackageName
        if (pubkey.isBlank() || pkg.isBlank()) {
            cachedSigner = null
            cachedIdentity = null
            return null
        }

        val identity = pubkey to pkg
        cachedSigner?.let { if (cachedIdentity == identity) return it }

        val signer = NostrSignerExternal(pubkey, pkg, Citrine.instance.contentResolver)
        // Lets the signer fall back to Amber's UI when the background ContentResolver
        // path can't auto-approve. The launcher routes through MainActivity's
        // ActivityResultLauncher (set via [registerActivityLauncher]), which preserves
        // callingPackage on the intent and feeds Amber's reply back into this signer via
        // [deliverSignerResponse] so the suspended sign() resumes.
        signer.registerForegroundLauncher { intent ->
            val launcher = activityLauncher
            if (launcher == null) {
                Log.w(Citrine.TAG, "No Activity launcher registered; open Citrine once to authorize the relay signer")
                return@registerForegroundLauncher
            }
            try {
                intent.`package` = pkg
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launcher(intent)
            } catch (e: Exception) {
                Log.e(Citrine.TAG, "Failed to launch relay signer intent via Activity launcher", e)
            }
        }
        cachedSigner = signer
        cachedIdentity = identity
        return signer
    }
}
