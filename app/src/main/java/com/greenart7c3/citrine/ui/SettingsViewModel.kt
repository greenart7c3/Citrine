package com.greenart7c3.citrine.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.greenart7c3.citrine.service.PokeyListener
import com.greenart7c3.citrine.service.RelayAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsViewModel(private val appContext: Context) : ViewModel() {
    /** Restores every setting to its default, persists it and then calls [onReset] on the main thread. */
    fun resetAllSettings(
        pokeyListener: PokeyListener,
        onApplyChanges: () -> Unit,
        onReset: () -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            Settings.defaultValues()
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            RelayAggregator.onConfigChanged()
            if (Settings.listenToPokeyBroadcasts) {
                pokeyListener.register()
            } else {
                pokeyListener.unregister()
            }
            onApplyChanges()
            viewModelScope.launch {
                onReset()
            }
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(appContext) }
        }
    }
}
