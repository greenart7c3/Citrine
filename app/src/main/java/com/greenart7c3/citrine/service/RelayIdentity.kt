package com.greenart7c3.citrine.service

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.utils.Hex

/**
 * The relay's NIP-29 signing identity, used for group metadata events (kinds
 * 39000-39003) and relay-issued membership changes (9000/9001). Two modes:
 *
 *  - Relay owner configured via the "login with Amber" flow in relay info settings:
 *    the owner's key is the relay identity, signed through the Amber (NIP-55)
 *    external signer.
 *  - No owner configured: a keypair is generated once, kept in [EncryptedStorage],
 *    and signs with [NostrSignerInternal] — so group management always works.
 */
object RelayIdentity {
    private lateinit var context: Context

    /** Hands the identity store the application context (read only once it is attached); called once from the Citrine constructor. */
    fun install(context: Context) {
        this.context = context
    }

    @Volatile
    private var cachedSigner: NostrSigner? = null

    @Volatile
    private var cachedSignerPubkeyAndPackage: Pair<String, String>? = null

    private val internalSignerPubkeyAndPackage = "" to ""

    @Volatile
    private var activityLauncher: ((Intent) -> Unit)? = null

    /**
     * Sets the Activity-scoped Intent launcher (MainActivity's) the Amber signer falls back to
     * when its background ContentResolver path can't auto-approve. It must be backed by
     * registerForActivityResult, which preserves the caller's identity on the intent: Amber
     * relies on that callingPackage to identify which app is asking, and a plain
     * Application-context startActivity() makes it null. Amber's reply comes back through
     * [deliverSignerResponse], which resumes the suspended sign().
     */
    fun registerActivityLauncher(launcher: (Intent) -> Unit) {
        activityLauncher = launcher
    }

    fun unregisterActivityLauncher() {
        activityLauncher = null
    }

    /** Routes an Intent result received by MainActivity into the suspended sign() call. */
    fun deliverSignerResponse(data: Intent) {
        (cachedSigner as? NostrSignerExternal)?.newResponse(data)
    }

    fun pubKeyHex(): String = signer().pubKey

    @Synchronized
    fun signer(): NostrSigner {
        val pubkey = Settings.ownerPubkey
        val pkg = Settings.relaySignerPackageName
        val pubkeyAndPackage = if (pubkey.isBlank() || pkg.isBlank()) internalSignerPubkeyAndPackage else pubkey to pkg

        cachedSigner?.let { if (cachedSignerPubkeyAndPackage == pubkeyAndPackage) return it }

        val signer = if (pubkeyAndPackage.first.isBlank()) {
            NostrSignerInternal(loadOrCreateKeyPair())
        } else {
            buildExternalSigner(pubkey, pkg)
        }
        cachedSigner = signer
        cachedSignerPubkeyAndPackage = pubkeyAndPackage
        return signer
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val prefs = EncryptedStorage.preferences(context)
        val stored = prefs.getString(PrefKeys.RELAY_PRIVATE_KEY, null)
        if (!stored.isNullOrBlank()) {
            return KeyPair(privKey = Hex.decode(stored))
        }
        return KeyPair().also {
            prefs.edit { putString(PrefKeys.RELAY_PRIVATE_KEY, it.privKey!!.toHexKey()) }
            Log.d(Citrine.TAG, "Generated new relay identity ${it.pubKey.toHexKey()}")
        }
    }

    private fun buildExternalSigner(pubkey: String, pkg: String): NostrSignerExternal {
        val signer = NostrSignerExternal(pubkey, pkg, context.contentResolver)
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
        return signer
    }
}
