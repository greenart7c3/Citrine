/**
 * Copyright (c) 2024 Vitor Pamplona
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
package com.greenart7c3.citrine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PokeyReceiver : BroadcastReceiver() {
    companion object {
        const val POKEY_ACTION = "com.shared.NOSTR"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == POKEY_ACTION) { // it's best practice to verify intent action before performing any operation
            val eventStr = intent.getStringExtra("EVENT")
            Log.d(Citrine.TAG, "New Pokey Notification Arrived $eventStr")

            if (eventStr == null) return

            scope.launch(Dispatchers.IO) {
                try {
                    CustomWebSocketService.server?.innerProcessEvent(Event.fromJson(eventStr), null)
                } catch (e: Exception) {
                    Log.e(Citrine.TAG, "Failed to parse Pokey Event", e)
                }
            }
        }
    }
}
