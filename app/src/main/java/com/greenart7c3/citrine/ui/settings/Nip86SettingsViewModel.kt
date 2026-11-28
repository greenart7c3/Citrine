package com.greenart7c3.citrine.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.database.AppDatabase

class Nip86SettingsViewModel(private val database: AppDatabase) : ViewModel() {
    /** Deletes every stored event of each banned [pubkeys] author the user chose to purge. */
    suspend fun purgeEventsOf(pubkeys: Collection<String>) {
        val eventDao = database.eventDao()
        pubkeys.forEach { eventDao.deleteByPubkey(it) }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { Nip86SettingsViewModel(AppDatabase.getDatabase(appContext)) }
        }
    }
}
