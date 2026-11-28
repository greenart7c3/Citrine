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
import com.greenart7c3.citrine.service.RelayAggregator
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AggregatorSettingsViewModel(private val appContext: Context) : ViewModel() {
    var isLoading by mutableStateOf(false)
        private set
    var relayAggregatorEnabled by mutableStateOf(Settings.relayAggregatorEnabled)
        private set
    var aggregatorPubkey by mutableStateOf(TextFieldValue(Settings.aggregatorPubkey))
    val aggregatorKinds = EditableSet(Settings.relayAggregatorKinds) { it.toIntOrNull() }
    var aggregatorRefreshMinutes by mutableStateOf(TextFieldValue(Settings.relayAggregatorRefreshMinutes.toString()))
    var aggregatorIncludeTagged by mutableStateOf(Settings.relayAggregatorIncludeTagged)
    var aggregatorWifiOnly by mutableStateOf(Settings.relayAggregatorWifiOnly)
    var aggregatorPauseOnLimitedNetwork by mutableStateOf(Settings.relayAggregatorPauseOnLimitedNetwork)
    val extraRelays = EditableSet(Settings.relayAggregatorExtraRelays) { normalizeRelayInput(it) }
    val sourceRelays = EditableSet(Settings.relayAggregatorSourceRelays) { normalizeRelayInput(it) }
    val indexerRelays = EditableSet(Settings.relayAggregatorIndexerRelays) { normalizeRelayInput(it) }
    private var aggregatorSignerPubkey by mutableStateOf(Settings.aggregatorSignerPubkey)
    private var aggregatorSignerPackageName by mutableStateOf(Settings.aggregatorSignerPackageName)

    /** Turns the aggregator on or off; false when it cannot be turned on without a pubkey or an extra relay. */
    fun changeEnabled(enabled: Boolean): Boolean {
        if (enabled && aggregatorPubkey.text.isBlank() && extraRelays.values.isEmpty()) return false
        relayAggregatorEnabled = enabled
        return true
    }

    /** Takes the aggregator key from the external signer's reply; a blank or unparsable [key] or [packageName] is ignored. */
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
        aggregatorPubkey = TextFieldValue(returnedKey)
        aggregatorSignerPubkey = returnedKey
        aggregatorSignerPackageName = packageName
    }

    /** Writes the edited aggregator setup to [Settings], persists it and tells [RelayAggregator]. */
    fun applyChanges(onApplyChanges: () -> Unit) {
        val refreshMinutes = aggregatorRefreshMinutes.text.toIntOrNull()?.takeIf { it >= 1 }
            ?: Settings.relayAggregatorRefreshMinutes
        val resolvedAggregatorKey = resolveAggregatorKey()
        val keepsSigner = resolvedAggregatorKey.isNotBlank() && resolvedAggregatorKey == aggregatorSignerPubkey
        val resolvedSignerPubkey = if (keepsSigner) aggregatorSignerPubkey else ""
        val resolvedSignerPackage = if (keepsSigner) aggregatorSignerPackageName else ""

        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            Settings.relayAggregatorEnabled = relayAggregatorEnabled
            Settings.aggregatorPubkey = resolvedAggregatorKey
            Settings.aggregatorSignerPubkey = resolvedSignerPubkey
            Settings.aggregatorSignerPackageName = resolvedSignerPackage
            Settings.relayAggregatorKinds = aggregatorKinds.values
            Settings.relayAggregatorRefreshMinutes = refreshMinutes
            Settings.relayAggregatorIncludeTagged = aggregatorIncludeTagged
            Settings.relayAggregatorWifiOnly = aggregatorWifiOnly
            Settings.relayAggregatorPauseOnLimitedNetwork = aggregatorPauseOnLimitedNetwork
            Settings.relayAggregatorExtraRelays = extraRelays.values
            Settings.relayAggregatorSourceRelays = sourceRelays.values
            Settings.relayAggregatorIndexerRelays = indexerRelays.values
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, appContext)

            aggregatorPubkey = TextFieldValue(resolvedAggregatorKey)
            aggregatorSignerPubkey = resolvedSignerPubkey
            aggregatorSignerPackageName = resolvedSignerPackage
            aggregatorRefreshMinutes = TextFieldValue(refreshMinutes.toString())

            RelayAggregator.onConfigChanged()
            onApplyChanges()
            delay(1500)
            isLoading = false
        }
    }

    private fun resolveAggregatorKey(): String {
        val aggregatorKeyTrimmed = aggregatorPubkey.text.trim()
        return if (aggregatorKeyTrimmed.isBlank()) {
            ""
        } else {
            aggregatorKeyTrimmed.toNostrKey() ?: aggregatorKeyTrimmed
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { AggregatorSettingsViewModel(appContext) }
        }
    }
}
