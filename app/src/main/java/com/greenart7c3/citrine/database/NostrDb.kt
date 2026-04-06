package com.greenart7c3.citrine.database

import android.util.Log

/**
 * JNI bindings to the nostrdb native library (`libcitrine_nostrdb.so`).
 *
 * The shared library is compiled from `app/src/main/cpp/` via the CMake build in
 * `app/build.gradle.kts`. The nostrdb C sources must be present before building —
 * run `scripts/setup_nostrdb.sh` to clone them.
 */
internal object NostrDb {
    private const val TAG = "NostrDb"

    init {
        try {
            System.loadLibrary("citrine_nostrdb")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load citrine_nostrdb native library", e)
            throw e
        }
    }

    /**
     * Opens (or creates) a nostrdb database directory at [dbPath].
     * @param mapSize LMDB memory-map size in bytes (default 1 GiB).
     * @return Opaque handle (native pointer cast to Long), or 0 on failure.
     */
    external fun nInit(dbPath: String, mapSize: Long = 1L * 1024 * 1024 * 1024): Long

    /** Closes the database and releases all resources held by [handle]. */
    external fun nClose(handle: Long)

    /**
     * Ingests a raw Nostr event JSON object into the database.
     * The JSON must be the event object itself (not wrapped in an array).
     * Signature verification is performed by nostrdb.
     * @return true if the event was newly inserted, false if it already existed or was invalid.
     */
    external fun nIngest(handle: Long, eventJson: String): Boolean

    /**
     * Queries events matching a NIP-01 filter JSON string.
     * @param filterJson NIP-01 filter, e.g. `{"kinds":[1],"authors":["abc..."],"limit":50}`
     * @param limit Maximum number of results to return.
     * @return Array of raw Nostr event JSON strings.
     */
    external fun nQuery(handle: Long, filterJson: String, limit: Int): Array<String>

    /**
     * Counts events matching a NIP-01 filter (up to [limit]).
     */
    external fun nCount(handle: Long, filterJson: String, limit: Int): Int

    /**
     * Returns hex-encoded IDs of every event stored in the database.
     * Capped at 2 million events.
     */
    external fun nAllIds(handle: Long): Array<String>

    /**
     * Deletes the event with the given hex ID.
     * @return 1 if deleted, 0 if not found.
     */
    external fun nDeleteById(handle: Long, idHex: String): Int

    /**
     * Deletes all events whose IDs are in [idHexArray].
     * @return number of events deleted.
     */
    external fun nDeleteByIds(handle: Long, idHexArray: Array<String>): Int

    /**
     * Returns a JSON array of per-kind event counts.
     * Format: `[{"kind":1,"count":42},{"kind":0,"count":5},...]`
     */
    external fun nCountByKind(handle: Long): String
}
