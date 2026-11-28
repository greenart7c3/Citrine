package com.greenart7c3.citrine.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.PokeyListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NetworkSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var startOnBoot by mutableStateOf(Settings.startOnBoot)
    var listenToPokeyBroadcasts by mutableStateOf(Settings.listenToPokeyBroadcasts)
    var sendMuteResponse by mutableStateOf(Settings.sendMuteResponse)
    var useProxy by mutableStateOf(Settings.useProxy)
    var proxyAllUrls by mutableStateOf(Settings.proxyAllUrls)
    var useTor by mutableStateOf(Settings.useTor)

    /** Writes the edited values to [Settings], persists them and follows the Pokey switch. */
    fun applyChanges(pokeyListener: PokeyListener, onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.startOnBoot = startOnBoot
            Settings.listenToPokeyBroadcasts = listenToPokeyBroadcasts
            Settings.sendMuteResponse = sendMuteResponse
            Settings.useProxy = useProxy
            Settings.proxyAllUrls = proxyAllUrls
            Settings.useTor = useTor
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            if (listenToPokeyBroadcasts) {
                pokeyListener.register()
            } else {
                pokeyListener.unregister()
            }
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { NetworkSettingsViewModel(appContext) }
        }
    }
}
