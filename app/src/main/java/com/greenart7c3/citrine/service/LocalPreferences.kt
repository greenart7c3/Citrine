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
    const val DELETE_EXPIRED_EVENTS = "delete_expired_events"
    const val DELETE_EPHEMERAL_EVENTS = "delete_ephemeral_events"
    const val USE_SSL = "use_ssl"
    const val HOST = "host"
    const val PORT = "port"
    const val NEVER_DELETE_FROM = "never_delete_from"
    const val RELAY_NAME = "relay_name"
    const val RELAY_OWNER_PUBKEY = "relay_owner_pubkey"
    const val RELAY_CONTACT = "relay_contact"
    const val RELAY_DESCRIPTION = "relay_description"
    const val RELAY_ICON = "relay_icon"
    const val AUTO_BACKUP = "auto_backup"
    const val AUTO_BACKUP_FOLDER = "auto_backup_folder"
    const val AUTH_ENABLED = "auth_enabled"
    const val LISTEN_TO_POKEY_BROADCASTS = "listen_to_pokey_broadcasts"
    const val START_ON_BOOT = "start_on_boot"
}

object LocalPreferences {
    private fun encryptedPreferences(
        context: Context,
    ): SharedPreferences {
        return EncryptedStorage.preferences(context)
    }

    fun shouldShowAutoBackupDialog(context: Context): Boolean {
        return !encryptedPreferences(context).contains(PrefKeys.AUTO_BACKUP)
    }

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
                putBoolean(PrefKeys.DELETE_EXPIRED_EVENTS, settings.deleteExpiredEvents)
                putBoolean(PrefKeys.DELETE_EPHEMERAL_EVENTS, settings.deleteEphemeralEvents)
                putBoolean(PrefKeys.USE_SSL, settings.useSSL)
                putString(PrefKeys.HOST, settings.host)
                putInt(PrefKeys.PORT, settings.port)
                putStringSet(PrefKeys.NEVER_DELETE_FROM, settings.neverDeleteFrom)
                putString(PrefKeys.RELAY_NAME, settings.name)
                putString(PrefKeys.RELAY_OWNER_PUBKEY, settings.ownerPubkey)
                putString(PrefKeys.RELAY_CONTACT, settings.contact)
                putString(PrefKeys.RELAY_DESCRIPTION, settings.description)
                putString(PrefKeys.RELAY_ICON, settings.relayIcon)
                putBoolean(PrefKeys.AUTO_BACKUP, settings.autoBackup)
                putString(PrefKeys.AUTO_BACKUP_FOLDER, settings.autoBackupFolder)
                putBoolean(PrefKeys.AUTH_ENABLED, settings.authEnabled)
                putBoolean(PrefKeys.LISTEN_TO_POKEY_BROADCASTS, settings.listenToPokeyBroadcasts)
                putBoolean(PrefKeys.START_ON_BOOT, settings.startOnBoot)
            }
        }
    }

    fun loadSettingsFromEncryptedStorage(context: Context) {
        val prefs = encryptedPreferences(context)
        Settings.allowedKinds = prefs.getString(PrefKeys.ALLOWED_KINDS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        Settings.allowedPubKeys = prefs.getStringSet(PrefKeys.ALLOWED_PUB_KEYS, emptySet()) ?: emptySet()
        Settings.allowedTaggedPubKeys = prefs.getStringSet(PrefKeys.ALLOWED_TAGGED_PUB_KEYS, emptySet()) ?: emptySet()
        Settings.deleteEventsOlderThan = OlderThan.valueOf(prefs.getString(PrefKeys.DELETE_EVENTS_OLDER_THAN, OlderThan.NEVER.toString()) ?: OlderThan.NEVER.toString())
        Settings.deleteExpiredEvents = prefs.getBoolean(PrefKeys.DELETE_EXPIRED_EVENTS, true)
        Settings.deleteEphemeralEvents = prefs.getBoolean(PrefKeys.DELETE_EPHEMERAL_EVENTS, true)
        Settings.useSSL = prefs.getBoolean(PrefKeys.USE_SSL, false)
        Settings.host = prefs.getString(PrefKeys.HOST, "127.0.0.1") ?: "127.0.0.1"
        Settings.port = prefs.getInt(PrefKeys.PORT, 4869)
        Settings.neverDeleteFrom = prefs.getStringSet(PrefKeys.NEVER_DELETE_FROM, emptySet()) ?: emptySet()
        Settings.name = prefs.getString(PrefKeys.RELAY_NAME, "Citrine") ?: "Citrine"
        Settings.ownerPubkey = prefs.getString(PrefKeys.RELAY_OWNER_PUBKEY, "") ?: ""
        Settings.contact = prefs.getString(PrefKeys.RELAY_CONTACT, "") ?: ""
        Settings.description = prefs.getString(PrefKeys.RELAY_DESCRIPTION, "A Nostr relay in your phone") ?: "A Nostr relay in your phone"
        Settings.relayIcon = prefs.getString(PrefKeys.RELAY_ICON, "https://github.com/greenart7c3/Citrine/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true") ?: "https://github.com/greenart7c3/Citrine/blob/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png?raw=true"
        Settings.autoBackup = prefs.getBoolean(PrefKeys.AUTO_BACKUP, false)
        Settings.autoBackupFolder = prefs.getString(PrefKeys.AUTO_BACKUP_FOLDER, "") ?: ""
        Settings.authEnabled = prefs.getBoolean(PrefKeys.AUTH_ENABLED, true)
        Settings.listenToPokeyBroadcasts = prefs.getBoolean(PrefKeys.LISTEN_TO_POKEY_BROADCASTS, true)
        Settings.startOnBoot = prefs.getBoolean(PrefKeys.START_ON_BOOT, true)
    }
}
