package com.greenart7c3.citrine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.HistoryDatabase
import com.greenart7c3.citrine.database.toEvent
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent

class ContactsViewModel(
    private val database: AppDatabase,
    private val history: AppDatabase,
) : ViewModel() {
    /** Every contact list of [pubKey] the history database kept, newest replacements included. */
    suspend fun contactLists(pubKey: String): List<ContactListEvent> = history.eventDao().getContactLists(pubKey).map { it.toEvent() as ContactListEvent }

    /** The stored NIP-65 relay list of [pubKey], if any. */
    suspend fun outboxRelays(pubKey: String): AdvertisedRelayListEvent? = database.eventDao().getAdvertisedRelayList(pubKey)?.toEvent() as? AdvertisedRelayListEvent

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContactsViewModel(AppDatabase.getDatabase(appContext), HistoryDatabase.getDatabase(appContext)) }
        }
    }
}
