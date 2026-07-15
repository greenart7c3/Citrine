package com.greenart7c3.citrine.service

import android.content.Context
import androidx.core.content.edit
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.Hex

/**
 * The relay's own NIP-29 signing identity. Group metadata events (kinds 39000-39003) and
 * relay-issued membership changes must be signed by the relay itself, so a keypair is
 * generated on first use and kept in [EncryptedStorage]. The private key never leaves
 * this object (in particular it is not mirrored into the [com.greenart7c3.citrine.server.Settings] singleton).
 */
object RelayIdentity {
    @Volatile
    private var signerInternal: NostrSignerInternal? = null

    fun signer(context: Context): NostrSignerInternal = signerInternal ?: synchronized(this) {
        signerInternal ?: run {
            val prefs = EncryptedStorage.preferences(context)
            val stored = prefs.getString(PrefKeys.RELAY_PRIVATE_KEY, null)
            val keyPair = if (stored.isNullOrBlank()) {
                KeyPair().also {
                    prefs.edit { putString(PrefKeys.RELAY_PRIVATE_KEY, it.privKey!!.toHexKey()) }
                    Log.d(Citrine.TAG, "Generated new relay identity ${it.pubKey.toHexKey()}")
                }
            } else {
                KeyPair(privKey = Hex.decode(stored))
            }
            NostrSignerInternal(keyPair).also { signerInternal = it }
        }
    }

    fun pubKeyHex(context: Context): String = signer(context).keyPair.pubKey.toHexKey()
}
