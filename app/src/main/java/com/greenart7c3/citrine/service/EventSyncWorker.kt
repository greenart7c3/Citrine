package com.greenart7c3.citrine.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.utils.Hex
import kotlin.coroutines.cancellation.CancellationException

class EventSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val pubkey = Settings.backgroundSyncPubkey
            if (pubkey.isBlank()) {
                Log.d(Citrine.TAG, "EventSyncWorker: No background sync pubkey configured, skipping")
                return Result.success()
            }

            val signer = NostrSignerInternal(KeyPair(pubKey = Hex.decode(pubkey)))
            val relays = EventDownloader.resolveDownloadRelays(signer, Settings.useRelayAggregatorForDownloads)
            if (relays.isEmpty()) {
                Log.d(Citrine.TAG, "EventSyncWorker: No relays resolved for background sync")
                return Result.success()
            }

            Log.d(Citrine.TAG, "EventSyncWorker: Starting background pull from ${relays.size} relays")
            EventDownloader.fetchEvents(
                signer = signer,
                relays = relays,
                downloadTaggedEvents = true,
            )
            Log.d(Citrine.TAG, "EventSyncWorker: Background pull complete")
            return Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(Citrine.TAG, "EventSyncWorker: Background pull failed", e)
            return Result.retry()
        }
    }
}
