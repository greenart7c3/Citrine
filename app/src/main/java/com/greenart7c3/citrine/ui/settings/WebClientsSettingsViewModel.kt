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
import com.greenart7c3.citrine.service.NsiteManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WebClientsSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var webPath by mutableStateOf(TextFieldValue(""))
    var webClients by mutableStateOf<Map<String, String>>(Settings.webClients.toMap())
        private set
    var nsites by mutableStateOf(Settings.nsites.toList())
        private set
    var updatesAvailable by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Serves the picked folder at the typed path and clears the path field. */
    fun addWebClient(folderUri: String) {
        val path = if (webPath.text.startsWith("/")) webPath.text else "/${webPath.text}"
        webClients = webClients + (path to folderUri)
        webPath = TextFieldValue("")
    }

    fun removeWebClient(path: String) {
        webClients = webClients - path
    }

    /** Re-reads the installed nsites from [Settings], which other screens change. */
    fun reloadNsites() {
        nsites = Settings.nsites.toList()
    }

    fun checkForUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            val updates = NsiteManager.checkForUpdates()
            updatesAvailable = updates.map { it.nsite.address }.toSet()
            nsites = Settings.nsites.toList()
        }
    }

    fun setAutoUpdate(address: String, enabled: Boolean) {
        NsiteManager.setAutoUpdate(address, enabled)
        nsites = Settings.nsites.toList()
    }

    fun applyUpdate(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NsiteManager.applyUpdateByAddress(address)
            updatesAvailable = updatesAvailable - address
            nsites = Settings.nsites.toList()
        }
    }

    fun deleteNsite(address: String) {
        NsiteManager.delete(address)
        nsites = Settings.nsites.toList()
    }

    /** Writes the edited web clients to [Settings] and persists them. */
    fun applyChanges(onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.webClients = webClients.toMutableMap()
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { WebClientsSettingsViewModel(appContext) }
        }
    }
}
