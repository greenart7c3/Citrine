package com.greenart7c3.citrine.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log

/** Registers and unregisters the [PokeyReceiver] for Pokey's broadcasts on the application context. */
class PokeyListener(private val context: Context) {
    private val pokeyReceiver = PokeyReceiver()
    private var isRegistered = false

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Synchronized
    fun register() {
        if (isRegistered) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                pokeyReceiver,
                IntentFilter(PokeyReceiver.POKEY_ACTION),
                Context.RECEIVER_EXPORTED,
            )
        } else {
            context.registerReceiver(
                pokeyReceiver,
                IntentFilter(PokeyReceiver.POKEY_ACTION),
            )
        }
        isRegistered = true
    }

    @Synchronized
    fun unregister() {
        if (!isRegistered) return

        try {
            context.unregisterReceiver(pokeyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(Citrine.TAG, "PokeyReceiver was not registered", e)
        }
        isRegistered = false
    }
}
