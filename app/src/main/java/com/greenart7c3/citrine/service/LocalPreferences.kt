package com.greenart7c3.citrine.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.greenart7c3.citrine.server.OlderThan
import com.greenart7c3.citrine.server.Settings

object PrefKeys {
    const val ALLOWED_KINDS = "allowed_kinds"
    const val ALLOWED_PUB_KEYS = "allowed_pub_keys"
    const val ALLOWED_TAGGED_PUB_KEYS = "allowed_tagged_pub_keys"
    const val DELETE_EVENTS_OLDER_THAN = "delete_events_older_than"
    const val USE_SSL = "use_ssl"
    const val HOST = "host"
    const val PORT = "port"
    const val NEVER_DELETE_FROM = "never_delete_from"
    const val PRESERVED_KINDS_FROM_DELETION = "preserved_kinds_from_deletion"
    const val RELAY_NAME = "relay_name"
    const val RELAY_OWNER_PUBKEY = "relay_owner_pubkey"
    const val RELAY_CONTACT = "relay_contact"
    const val RELAY_DESCRIPTION = "relay_description"
    const val RELAY_ICON = "relay_icon"
    const val AUTO_BACKUP = "auto_backup"
    const val AUTO_BACKUP_FOLDER = "auto_backup_folder"
    const val AUTH_ENABLED = "auth_enabled"
    const val SEND_MUTE_RESPONSE = "send_mute_response"
    const val LISTEN_TO_POKEY_BROADCASTS = "listen_to_pokey_broadcasts"
    const val START_ON_BOOT = "start_on_boot"
    const val LAST_BACKUP = "last_backup"
    const val USE_PROXY = "use_proxy"
    const val PROXY_ALL_URLS = "proxy_all_urls"
    const val USE_TOR = "use_tor"
    const val ONION_HOSTNAME = "onion_hostname"

    const val WEB_CLIENTS = "web_clients"
    const val NSITES = "nsites"
    const val LAST_NSITE_CHECK = "last_nsite_check"

    const val RELAY_AGGREGATOR_ENABLED = "relay_aggregator_enabled"
    const val AGGREGATOR_PUBKEY = "aggregator_pubkey"
    const val RELAY_AGGREGATOR_KINDS = "relay_aggregator_kinds"
    const val RELAY_AGGREGATOR_REFRESH_MINUTES = "relay_aggregator_refresh_minutes"
    const val RELAY_AGGREGATOR_INCLUDE_TAGGED = "relay_aggregator_include_tagged"
    const val RELAY_AGGREGATOR_LAST_SYNC = "relay_aggregator_last_sync"
    const val RELAY_AGGREGATOR_EXTRA_RELAYS = "relay_aggregator_extra_relays"
    const val RELAY_AGGREGATOR_SOURCE_RELAYS = "relay_aggregator_source_relays"
    const val RELAY_AGGREGATOR_INDEXER_RELAYS = "relay_aggregator_indexer_relays"
    const val RELAY_AGGREGATOR_WIFI_ONLY = "relay_aggregator_wifi_only"
    const val RELAY_AGGREGATOR_PAUSE_ON_LIMITED_NETWORK = "relay_aggregator_pause_on_limited_network"
    const val AGGREGATOR_SIGNER_PUBKEY = "aggregator_signer_pubkey"
    const val AGGREGATOR_SIGNER_PACKAGE_NAME = "aggregator_signer_package_name"
}

object LocalPreferences {
    private fun encryptedPreferences(
        context: Context,
    ): SharedPreferences = EncryptedStorage.preferences(context)

    fun shouldShowAutoBackupDialog(context: Context): Boolean = !encryptedPreferences(context).contains(PrefKeys.AUTO_BACKUP)

    fun saveSettingsToEncryptedStorage(settings: Settings, context: Context) {
        encryptedPreferences(context).edit {
            apply {
                if (settings.allowedKinds.isEmpty()) {
                    remove(PrefKeys.ALLOWED_KINDS)
                } else {
                    putString(PrefKeys.ALLOWED_KINDS, settings.allowedKinds.joinToString(","))
                }
                putStringSet(PrefKeys.ALLOWED_PUB_KEYS, settings.allowedPubKeys)
                putStringSet(PrefKeys.ALLOWED_TAGGED_PUB_KEYS, settings.allowedTaggedPubKeys)
                putString(PrefKeys.DELETE_EVENTS_OLDER_THAN, settings.deleteEventsOlderThan.toString())
                putBoolean(PrefKeys.USE_SSL, settings.useSSL)
                putString(PrefKeys.HOST, settings.host)
                putInt(PrefKeys.PORT, settings.port)
                putStringSet(PrefKeys.NEVER_DELETE_FROM, settings.neverDeleteFrom)
                putString(
                    PrefKeys.PRESERVED_KINDS_FROM_DELETION,
                    settings.preservedKindsFromDeletion.joinToString(","),
                )
                putString(PrefKeys.RELAY_NAME, settings.name)
                putString(PrefKeys.RELAY_OWNER_PUBKEY, settings.ownerPubkey)
                putString(PrefKeys.RELAY_CONTACT, settings.contact)
                putString(PrefKeys.RELAY_DESCRIPTION, settings.description)
                putString(PrefKeys.RELAY_ICON, settings.relayIcon)
                putBoolean(PrefKeys.AUTO_BACKUP, settings.autoBackup)
                putString(PrefKeys.AUTO_BACKUP_FOLDER, settings.autoBackupFolder)
                putBoolean(PrefKeys.AUTH_ENABLED, settings.authEnabled)
                putBoolean(PrefKeys.SEND_MUTE_RESPONSE, settings.sendMuteResponse)
                putBoolean(PrefKeys.LISTEN_TO_POKEY_BROADCASTS, settings.listenToPokeyBroadcasts)
                putBoolean(PrefKeys.START_ON_BOOT, settings.startOnBoot)
                putLong(PrefKeys.LAST_BACKUP, settings.lastBackup)
                putBoolean(PrefKeys.USE_PROXY, settings.useProxy)
                putBoolean(PrefKeys.PROXY_ALL_URLS, settings.proxyAllUrls)
                putBoolean(PrefKeys.USE_TOR, settings.useTor)
                putString(PrefKeys.ONION_HOSTNAME, settings.onionHostname)
                if (Settings.webClients.isNotEmpty()) {
                    putString(PrefKeys.WEB_CLIENTS, Settings.webClientsToJson())
                } else {
                    remove(PrefKeys.WEB_CLIENTS)
                }
                if (settings.nsites.isNotEmpty()) {
                    putString(PrefKeys.NSITES, Settings.nsitesToJson())
                } else {
                    remove(PrefKeys.NSITES)
                }
                putLong(PrefKeys.LAST_NSITE_CHECK, settings.lastNsiteCheck)

                putBoolean(PrefKeys.RELAY_AGGREGATOR_ENABLED, settings.relayAggregatorEnabled)
                putString(PrefKeys.AGGREGATOR_PUBKEY, settings.aggregatorPubkey)
                if (settings.relayAggregatorKinds.isEmpty()) {
                    remove(PrefKeys.RELAY_AGGREGATOR_KINDS)
                } else {
                    putString(PrefKeys.RELAY_AGGREGATOR_KINDS, settings.relayAggregatorKinds.joinToString(","))
                }
                putInt(PrefKeys.RELAY_AGGREGATOR_REFRESH_MINUTES, settings.relayAggregatorRefreshMinutes)
                putBoolean(PrefKeys.RELAY_AGGREGATOR_INCLUDE_TAGGED, settings.relayAggregatorIncludeTagged)
                putLong(PrefKeys.RELAY_AGGREGATOR_LAST_SYNC, settings.relayAggregatorLastSync)
                putStringSet(PrefKeys.RELAY_AGGREGATOR_EXTRA_RELAYS, settings.relayAggregatorExtraRelays)
                putStringSet(PrefKeys.RELAY_AGGREGATOR_SOURCE_RELAYS, settings.relayAggregatorSourceRelays)
                putStringSet(PrefKeys.RELAY_AGGREGATOR_INDEXER_RELAYS, settings.relayAggregatorIndexerRelays)
                putBoolean(PrefKeys.RELAY_AGGREGATOR_WIFI_ONLY, settings.relayAggregatorWifiOnly)
                putBoolean(PrefKeys.RELAY_AGGREGATOR_PAUSE_ON_LIMITED_NETWORK, settings.relayAggregatorPauseOnLimitedNetwork)
                putString(PrefKeys.AGGREGATOR_SIGNER_PUBKEY, settings.aggregatorSignerPubkey)
                putString(PrefKeys.AGGREGATOR_SIGNER_PACKAGE_NAME, settings.aggregatorSignerPackageName)
            }
        }
    }

    fun loadSettingsFromEncryptedStorage(context: Context) {
        val prefs = encryptedPreferences(context)
        Settings.allowedKinds = prefs.getString(PrefKeys.ALLOWED_KINDS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        Settings.allowedPubKeys = prefs.getStringSet(PrefKeys.ALLOWED_PUB_KEYS, emptySet()) ?: emptySet()
        Settings.allowedTaggedPubKeys = prefs.getStringSet(PrefKeys.ALLOWED_TAGGED_PUB_KEYS, emptySet()) ?: emptySet()
        Settings.deleteEventsOlderThan = OlderThan.valueOf(prefs.getString(PrefKeys.DELETE_EVENTS_OLDER_THAN, OlderThan.NEVER.toString()) ?: OlderThan.NEVER.toString())
        Settings.useSSL = prefs.getBoolean(PrefKeys.USE_SSL, false)
        Settings.host = prefs.getString(PrefKeys.HOST, "127.0.0.1") ?: "127.0.0.1"
        Settings.port = prefs.getInt(PrefKeys.PORT, 4869)
        Settings.neverDeleteFrom = prefs.getStringSet(PrefKeys.NEVER_DELETE_FROM, emptySet()) ?: emptySet()
        Settings.preservedKindsFromDeletion = prefs.getString(PrefKeys.PRESERVED_KINDS_FROM_DELETION, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: setOf(0, 3, 10000, 10002)
        Settings.name = prefs.getString(PrefKeys.RELAY_NAME, "Citrine") ?: "Citrine"
        Settings.ownerPubkey = prefs.getString(PrefKeys.RELAY_OWNER_PUBKEY, "") ?: ""
        Settings.contact = prefs.getString(PrefKeys.RELAY_CONTACT, "") ?: ""
        Settings.description = prefs.getString(PrefKeys.RELAY_DESCRIPTION, "A Nostr relay in your phone") ?: "A Nostr relay in your phone"
        Settings.relayIcon = prefs.getString(PrefKeys.RELAY_ICON, "https://github.com/greenart7c3/Citrine/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true") ?: "https://github.com/greenart7c3/Citrine/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true"
        Settings.autoBackup = prefs.getBoolean(PrefKeys.AUTO_BACKUP, false)
        Settings.autoBackupFolder = prefs.getString(PrefKeys.AUTO_BACKUP_FOLDER, "") ?: ""
        Settings.authEnabled = prefs.getBoolean(PrefKeys.AUTH_ENABLED, false)
        Settings.sendMuteResponse = prefs.getBoolean(PrefKeys.SEND_MUTE_RESPONSE, false)
        Settings.listenToPokeyBroadcasts = prefs.getBoolean(PrefKeys.LISTEN_TO_POKEY_BROADCASTS, true)
        Settings.startOnBoot = prefs.getBoolean(PrefKeys.START_ON_BOOT, true)
        Settings.lastBackup = prefs.getLong(PrefKeys.LAST_BACKUP, 0)
        Settings.useProxy = prefs.getBoolean(PrefKeys.USE_PROXY, false)
        // Migration: pre-existing installs with proxy enabled used to route every URL through Tor.
        // Preserve that behavior unless the user has explicitly chosen otherwise.
        Settings.proxyAllUrls = prefs.getBoolean(PrefKeys.PROXY_ALL_URLS, Settings.useProxy)
        Settings.useTor = prefs.getBoolean(PrefKeys.USE_TOR, false)
        Settings.onionHostname = prefs.getString(PrefKeys.ONION_HOSTNAME, "") ?: ""
        prefs.getString(PrefKeys.WEB_CLIENTS, null)?.let {
            Settings.webClients = Settings.webClientFromJson(it)
        }
        prefs.getString(PrefKeys.NSITES, null)?.let {
            Settings.nsites = Settings.nsitesFromJson(it)
        }
        Settings.lastNsiteCheck = prefs.getLong(PrefKeys.LAST_NSITE_CHECK, 0L)

        Settings.relayAggregatorEnabled = prefs.getBoolean(PrefKeys.RELAY_AGGREGATOR_ENABLED, false)
        Settings.aggregatorPubkey = prefs.getString(PrefKeys.AGGREGATOR_PUBKEY, "") ?: ""
        Settings.relayAggregatorKinds = prefs.getString(PrefKeys.RELAY_AGGREGATOR_KINDS, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: setOf(0, 1, 3, 5, 6, 7, 1111, 10002, 30023)
        Settings.relayAggregatorRefreshMinutes = prefs.getInt(PrefKeys.RELAY_AGGREGATOR_REFRESH_MINUTES, 60)
        Settings.relayAggregatorIncludeTagged = prefs.getBoolean(PrefKeys.RELAY_AGGREGATOR_INCLUDE_TAGGED, true)
        Settings.relayAggregatorLastSync = prefs.getLong(PrefKeys.RELAY_AGGREGATOR_LAST_SYNC, 0L)
        Settings.relayAggregatorExtraRelays = prefs.getStringSet(PrefKeys.RELAY_AGGREGATOR_EXTRA_RELAYS, emptySet()) ?: emptySet()
        Settings.relayAggregatorSourceRelays = prefs.getStringSet(PrefKeys.RELAY_AGGREGATOR_SOURCE_RELAYS, null)
            ?: Settings.DEFAULT_AGGREGATOR_SOURCE_RELAYS
        Settings.relayAggregatorIndexerRelays = prefs.getStringSet(PrefKeys.RELAY_AGGREGATOR_INDEXER_RELAYS, null)
            ?: Settings.DEFAULT_NIP65_INDEXER_RELAYS
        Settings.relayAggregatorWifiOnly = prefs.getBoolean(PrefKeys.RELAY_AGGREGATOR_WIFI_ONLY, true)
        Settings.relayAggregatorPauseOnLimitedNetwork = prefs.getBoolean(PrefKeys.RELAY_AGGREGATOR_PAUSE_ON_LIMITED_NETWORK, true)
        Settings.aggregatorSignerPubkey = prefs.getString(PrefKeys.AGGREGATOR_SIGNER_PUBKEY, "") ?: ""
        Settings.aggregatorSignerPackageName = prefs.getString(PrefKeys.AGGREGATOR_SIGNER_PACKAGE_NAME, "") ?: ""
    }
}
