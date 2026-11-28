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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AccessControlSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    val allowedPubKeys = EditableSet(Settings.allowedPubKeys) { it.toNostrKey() }
    val allowedTaggedPubKeys = EditableSet(Settings.allowedTaggedPubKeys) { it.toNostrKey() }
    val allowedKinds = EditableSet(Settings.allowedKinds) { it.toIntOrNull() }
    val rejectedKinds = EditableSet(Settings.rejectedKinds) { it.toIntOrNull() }

    /** Writes the edited access rules to [Settings] and persists them. */
    fun applyChanges(onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.allowedPubKeys = allowedPubKeys.values
            Settings.allowedTaggedPubKeys = allowedTaggedPubKeys.values
            Settings.allowedKinds = allowedKinds.values
            Settings.rejectedKinds = rejectedKinds.values
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccessControlSettingsViewModel(appContext) }
        }
    }
}
