package com.greenart7c3.citrine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.service.EventDownloader
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent

class DownloadYourEventsViewModel(private val database: AppDatabase) : ViewModel() {
    /** The [signer]'s contact list: the stored one, else fetched from the network. */
    suspend fun contactList(signer: NostrSigner): ContactListEvent? = database.eventDao().getContactList(signer.pubKey)?.toEvent() as? ContactListEvent
        ?: EventDownloader.fetchContactList(signer = signer)

    /** The [signer]'s NIP-65 relay list: the stored one, else fetched from the network. */
    suspend fun relayList(signer: NostrSigner): AdvertisedRelayListEvent? = database.eventDao().getAdvertisedRelayList(signer.pubKey)?.toEvent() as? AdvertisedRelayListEvent
        ?: EventDownloader.fetchAdvertisedRelayList(signer = signer)

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { DownloadYourEventsViewModel(AppDatabase.getDatabase(appContext)) }
        }
    }
}
