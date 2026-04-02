package com.greenart7c3.citrine.database

import android.content.Context
import com.greenart7c3.citrine.server.DatabaseBackend
import com.greenart7c3.citrine.server.Settings

/**
 * Creates [EventStore] instances based on [Settings.databaseBackend].
 *
 * Use [create] for the primary event store and [createHistory] for the
 * archive/history store (Room-only; returns null for non-Room backends).
 */
object EventStoreFactory {

    fun create(context: Context): EventStore = when (Settings.databaseBackend) {
        DatabaseBackend.ROOM -> RoomEventStore(AppDatabase.getDatabase(context))
        DatabaseBackend.NOSTRDB -> NostrDbEventStore()
        DatabaseBackend.RUST_NOSTR_LMDB -> RustNostrEventStore()
    }

    /**
     * Returns the history/archive store used to preserve old versions of
     * replaceable events. Only Room supports this; other backends return null.
     */
    fun createHistory(context: Context): EventStore? = when (Settings.databaseBackend) {
        DatabaseBackend.ROOM -> RoomEventStore(HistoryDatabase.getDatabase(context))
        else -> null
    }
}
