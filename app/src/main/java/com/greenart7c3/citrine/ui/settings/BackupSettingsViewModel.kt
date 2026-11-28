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

class BackupSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var autoBackup by mutableStateOf(Settings.autoBackup)
        private set
    private var autoBackupFolder by mutableStateOf(Settings.autoBackupFolder)

    /** Turns auto backup on into the picked folder. */
    fun enableAutoBackup(folderUri: String) {
        autoBackup = true
        autoBackupFolder = folderUri
    }

    fun disableAutoBackup() {
        autoBackup = false
    }

    /** Writes the backup setup to [Settings] and persists it. */
    fun applyChanges(onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.autoBackup = autoBackup
            Settings.autoBackupFolder = autoBackupFolder
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { BackupSettingsViewModel(appContext) }
        }
    }
}
