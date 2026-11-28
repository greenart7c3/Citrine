package com.greenart7c3.citrine.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.OlderThanType
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RetentionSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var neverDeleteFrom by mutableStateOf(Settings.neverDeleteFrom)
        private set
    var preservedKindsFromDeletion by mutableStateOf(Settings.preservedKindsFromDeletion)
        private set
    var preservedKindInput by mutableStateOf(TextFieldValue(""))
    var deleteFrom by mutableStateOf(TextFieldValue(""))
    var olderThanTypeIndex by mutableIntStateOf(
        OlderThanType.entries.toTypedArray()
            .indexOfFirst { it.name == Settings.deleteEventsOlderThan.toString() },
    )

    /** Puts the pasted [text] in the field and adds it when it is a key; false when it is not. */
    fun pasteNeverDeleteFrom(text: String): Boolean {
        deleteFrom = TextFieldValue(text)
        return addNeverDeleteFrom()
    }

    /** Adds the typed key to the never-delete list; false when it is not a key. */
    fun addNeverDeleteFrom(): Boolean {
        val key = deleteFrom.text.toNostrKey() ?: return false
        neverDeleteFrom = neverDeleteFrom + key
        deleteFrom = TextFieldValue("")
        return true
    }

    fun removeNeverDeleteFrom(pubkey: String) {
        neverDeleteFrom = neverDeleteFrom - pubkey
    }

    /** Puts the pasted [text] in the field and adds it when it is a kind; false when it is not. */
    fun pastePreservedKind(text: String): Boolean {
        preservedKindInput = TextFieldValue(text)
        return addPreservedKind()
    }

    /** Adds the typed kind to the preserved kinds; false when it is not a number. */
    fun addPreservedKind(): Boolean {
        val k = preservedKindInput.text.toIntOrNull() ?: return false
        preservedKindsFromDeletion = preservedKindsFromDeletion + k
        preservedKindInput = TextFieldValue("")
        return true
    }

    fun removePreservedKind(k: Int) {
        preservedKindsFromDeletion = preservedKindsFromDeletion - k
    }

    /** Writes the edited retention rules to [Settings] and persists them. */
    fun applyChanges(onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.neverDeleteFrom = neverDeleteFrom
            Settings.preservedKindsFromDeletion = preservedKindsFromDeletion
            Settings.deleteEventsOlderThan = when (olderThanTypeIndex) {
                OlderThanType.NEVER.screenCode -> OlderThan.NEVER
                OlderThanType.DAY.screenCode -> OlderThan.DAY
                OlderThanType.WEEK.screenCode -> OlderThan.WEEK
                OlderThanType.MONTH.screenCode -> OlderThan.MONTH
                OlderThanType.YEAR.screenCode -> OlderThan.YEAR
                else -> OlderThan.NEVER
            }
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { RetentionSettingsViewModel(appContext) }
        }
    }
}
