package com.greenart7c3.citrine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.service.EventDownloader
import com.greenart7c3.citrine.service.EventRebroadcaster
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.utils.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the user confirmed to rebroadcast. */
data class RebroadcastRequest(
    val relays: List<NormalizedRelayUrl>,
    val authorPubKey: String?,
    val kinds: Set<Int>,
    val since: Long?,
    val until: Long?,
    val includePrivateKinds: Boolean,
)

class RebroadcastViewModel(
    private val database: AppDatabase,
    private val appScope: CoroutineScope,
) : ViewModel() {
    /**
     * The [author]'s write relays: their NIP-65 list (stored, else fetched) or, when that names
     * none, the relays of their contact list (stored, else fetched).
     */
    suspend fun authorRelays(author: String): List<NormalizedRelayUrl> {
        val signer = NostrSignerInternal(KeyPair(pubKey = Hex.decode(author)))

        var advertised = database.eventDao().getAdvertisedRelayList(author)?.toEvent() as? AdvertisedRelayListEvent
        if (advertised == null) {
            advertised = EventDownloader.fetchAdvertisedRelayList(signer)
        }
        val found = mutableListOf<NormalizedRelayUrl>()
        advertised?.writeRelays()?.forEach { url ->
            RelayUrlNormalizer.normalizeOrNull(url)?.let { found.add(it) }
        }

        if (found.isEmpty()) {
            var contactList = database.eventDao().getContactList(author)?.toEvent() as? ContactListEvent
            if (contactList == null) {
                contactList = EventDownloader.fetchContactList(signer)
            }
            contactList?.relays()?.forEach { relay ->
                RelayUrlNormalizer.normalizeOrNull(relay.key.url)?.let { found.add(it) }
            }
        }
        return found
    }

    /** Replaces the running background job with the rebroadcast of [request], on the app scope. */
    fun rebroadcast(request: RebroadcastRequest) {
        Citrine.cancelJob()
        Citrine.job = appScope.launch(Dispatchers.IO) {
            EventRebroadcaster.rebroadcast(
                database = database,
                relays = request.relays,
                authorPubKey = request.authorPubKey,
                kinds = request.kinds,
                since = request.since,
                until = request.until,
                includePrivateKinds = request.includePrivateKinds,
            )
        }
    }

    companion object {
        fun factory(appContext: Context, appScope: CoroutineScope): ViewModelProvider.Factory = viewModelFactory {
            initializer { RebroadcastViewModel(AppDatabase.getDatabase(appContext), appScope) }
        }
    }
}
