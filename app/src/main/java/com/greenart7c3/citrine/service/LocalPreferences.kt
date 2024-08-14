package com.greenart7c3.citrine.service

import android.content.Context
import android.content.SharedPreferences
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
}

object LocalPreferences {
    private fun encryptedPreferences(
        context: Context,
    ): SharedPreferences {
        return EncryptedStorage.preferences(context)
    }

    fun saveSettingsToEncryptedStorage(settings: Settings, context: Context) {
        encryptedPreferences(context).edit().apply {
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
        }.apply()
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
    }
}
