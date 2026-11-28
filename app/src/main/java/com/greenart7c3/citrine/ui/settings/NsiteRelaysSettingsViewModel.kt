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
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NsiteRelaysSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var nsiteRelays by mutableStateOf(Settings.nsiteRelays)
        private set
    var nsiteRelayInput by mutableStateOf(TextFieldValue(""))

    /** Adds the typed relay to the list; false when it is not a relay address. */
    fun addNsiteRelay(): Boolean {
        val normalized = normalizeRelayInput(nsiteRelayInput.text) ?: return false
        nsiteRelays = nsiteRelays + normalized
        nsiteRelayInput = TextFieldValue("")
        return true
    }

    fun removeNsiteRelay(relay: String) {
        nsiteRelays = nsiteRelays - relay
    }

    fun resetNsiteRelays() {
        nsiteRelays = Settings.DEFAULT_NSITE_RELAYS
    }

    /** Writes the edited relays to [Settings] and persists them. */
    fun applyChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.nsiteRelays = nsiteRelays
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            delay(800)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { NsiteRelaysSettingsViewModel(appContext) }
        }
    }
}
