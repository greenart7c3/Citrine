package com.greenart7c3.citrine.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greenart7c3.citrine.R
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RelayInfoSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var host by mutableStateOf(TextFieldValue(Settings.host))
    var port by mutableStateOf(TextFieldValue(Settings.port.toString()))
    var relayName by mutableStateOf(TextFieldValue(Settings.name))
    var relayOwnerPubkey by mutableStateOf(Settings.ownerPubkey)
        private set
    private var relaySignerPackageName by mutableStateOf(Settings.relaySignerPackageName)
    var relayContact by mutableStateOf(TextFieldValue(Settings.contact))
    var relayDescription by mutableStateOf(TextFieldValue(Settings.description))
    var maxEventSize by mutableStateOf(TextFieldValue(Settings.maxEventSize.toString()))
    var relayIconUrl by mutableStateOf(TextFieldValue(Settings.relayIcon))

    /** Takes the owner from the external signer's reply; a blank or unparsable [key] or [packageName] is ignored. */
    fun loginWithExternalSigner(key: String, packageName: String) {
        val returnedKey = if (key.startsWith("npub")) {
            when (val parsed = Nip19Parser.uriToRoute(key)?.entity) {
                is NPub -> parsed.hex
                else -> ""
            }
        } else {
            key
        }
        if (returnedKey.isBlank() || packageName.isBlank()) return
        relayOwnerPubkey = returnedKey
        relaySignerPackageName = packageName
    }

    fun logout() {
        relayOwnerPubkey = ""
        relaySignerPackageName = ""
    }

    /** Validates the form and, when it is valid, saves it; returns the message of the first invalid field, else null. */
    @StringRes
    fun applyChanges(onApplyChanges: () -> Unit): Int? {
        val error = when {
            !isIpValid(host.text) || host.text.isBlank() -> R.string.invalid_host_or_port
            port.text.isBlank() || !port.text.isDigitsOnly() -> R.string.invalid_host_or_port
            relayIconUrl.text.isNotBlank() && runCatching { relayIconUrl.text.toUri() }.isFailure -> R.string.invalid_icon_url
            maxEventSize.text.isBlank() || !maxEventSize.text.isDigitsOnly() || maxEventSize.text.toIntOrNull() == null -> R.string.invalid_max_event_size
            else -> null
        }
        if (error == null) save(onApplyChanges)
        return error
    }

    private fun save(onApplyChanges: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.maxEventSize = maxEventSize.text.toInt()
            Settings.host = host.text
            Settings.port = port.text.toInt()
            Settings.name = relayName.text
            Settings.ownerPubkey = relayOwnerPubkey
            Settings.relaySignerPackageName = relaySignerPackageName
            Settings.contact = relayContact.text
            Settings.description = relayDescription.text
            Settings.relayIcon = relayIconUrl.text
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { RelayInfoSettingsViewModel(appContext) }
        }
    }
}
