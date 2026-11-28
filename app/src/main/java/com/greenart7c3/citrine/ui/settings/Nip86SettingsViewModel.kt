package com.greenart7c3.citrine.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val EVENT_ID_REGEX = Regex("^[0-9a-f]{64}$")

/** A ban list a settings screen edits, banned entry to reason, with the field a new entry is typed in; [parse] turns the typed text into an entry, or null when it is invalid. */
class EditableBanList(initial: Map<String, String>, private val parse: (String) -> String?) {
    var entries by mutableStateOf(initial)
        private set
    var input by mutableStateOf(TextFieldValue(""))

    /** Bans the typed entry and clears the field; returns the banned entry, or null when it is invalid. */
    fun add(): String? {
        val entry = parse(input.text) ?: return null
        entries = entries + (entry to "")
        input = TextFieldValue("")
        return entry
    }

    /** Puts the pasted [text] in the field and bans it; returns the banned entry, or null when it is invalid. */
    fun paste(text: String): String? {
        input = TextFieldValue(text)
        return add()
    }

    fun remove(entry: String) {
        entries = entries - entry
    }
}

class Nip86SettingsViewModel(
    private val appContext: Context,
    private val database: AppDatabase,
) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    val bannedPubKeys = EditableBanList(Settings.bannedPubKeys.toMap()) { it.toNostrKey() }
    val bannedEventIds = EditableBanList(Settings.bannedEventIds.toMap()) { text ->
        text.trim().lowercase().takeIf { EVENT_ID_REGEX.matches(it) }
    }
    val blockedIps = EditableBanList(Settings.blockedIps.toMap()) { text ->
        text.trim().takeIf { isIpValid(it) }
    }

    /**
     * Pubkeys the user opted to purge from the database. Deletion runs on
     * Apply, so backing out of the screen without applying deletes nothing.
     */
    var pubkeysToPurge by mutableStateOf(setOf<String>())
        private set

    fun purgeOnApply(pubkey: String) {
        pubkeysToPurge = pubkeysToPurge + pubkey
    }

    /** Writes the bans to [Settings], persists them and purges the events of the banned authors the user chose. */
    fun applyChanges(onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.bannedPubKeys = java.util.concurrent.ConcurrentHashMap(bannedPubKeys.entries)
            Settings.bannedEventIds = java.util.concurrent.ConcurrentHashMap(bannedEventIds.entries)
            Settings.blockedIps = java.util.concurrent.ConcurrentHashMap(blockedIps.entries)
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            purgeEventsOf(pubkeysToPurge.intersect(bannedPubKeys.entries.keys))
            pubkeysToPurge = emptySet()
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    /** Deletes every stored event of each banned [pubkeys] author the user chose to purge. */
    suspend fun purgeEventsOf(pubkeys: Collection<String>) {
        val eventDao = database.eventDao()
        pubkeys.forEach { eventDao.deleteByPubkey(it) }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { Nip86SettingsViewModel(appContext, AppDatabase.getDatabase(appContext)) }
        }
    }
}
