package com.greenart7c3.citrine.database

import android.content.Context
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.LocalPreferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * Removes duplicate [TagEntity] rows left behind by Citrine 3.0.0–3.2.1.
 *
 * Those versions dropped `deletetags()` from `insertEventWithTags()` assuming Room's
 * `@Insert(onConflict = IGNORE)` returned `null` on conflict, when it actually returned `-1L`
 * (non-null). Every re-ingestion of an existing replaceable event (kind 3, 10000, 10002, 0,
 * 30000+, notably on every hourly aggregator refresh) appended another full copy of its tags.
 *
 * Deduplication keeps the earliest row (`MIN(pk)`) per `(pkEvent, position)`, restoring the
 * original tag list so the event verifies again and stays within memory bounds.
 */
object TagDeduplicator {
    internal const val DELETE_CHUNK_SIZE = 10_000
    private const val SCAN_PAGE_SIZE = 50

    // Replaceable kinds hit hardest by hourly refreshes — repair these first so contact lists,
    // mute lists, relay lists and profiles are clean right away.
    private val PRIORITY_KINDS = intArrayOf(3, 10000, 10002, 0)

    private val running = AtomicBoolean(false)

    /**
     * Deletes duplicate `(pkEvent, position)` rows for [eventId], keeping the earliest `pk`
     * per position. Returns the total number of duplicate rows removed.
     *
     * Deletes in bounded SQLite chunks using `tags_by_pk_event` + rowid (`pk`) ordering so
     * even an event with millions of duplicate rows is repaired with O(1) Java heap and
     * without holding a long write lock.
     */
    suspend fun deduplicateEvent(
        dao: EventDao,
        eventId: String,
        deleteChunkSize: Int = DELETE_CHUNK_SIZE,
    ): Int {
        val stats = dao.getTagDuplicateStats(eventId)
        if (stats.totalCount <= stats.distinctCount) return 0
        val maxKeepPk = stats.maxKeepPk ?: return 0

        var removed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val deleted = dao.deleteEventTagsAfterPk(eventId, maxKeepPk, deleteChunkSize)
            removed += deleted
            if (deleted < deleteChunkSize) break
            yield()
        }
        // Catch any duplicate with pk <= maxKeepPk (e.g. interleaved inserts).
        removed += dao.deleteRemainingDuplicateTags(eventId)
        if (removed > 0) {
            Log.d(Citrine.TAG, "Deduplicated $removed duplicate tag row(s) for event $eventId (kept ${stats.distinctCount})")
        }
        return removed
    }

    /**
     * One-shot background pass over both [database] and [HistoryDatabase]. Runs once per
     * install (persisted via [Settings.duplicateTagsCleaned]); if cancelled or deferred by an
     * active import, leaves the flag unset so the next startup resumes cleanly.
     */
    suspend fun cleanDatabases(database: AppDatabase, context: Context) {
        if (Settings.duplicateTagsCleaned) return
        if (!running.compareAndSet(false, true)) return
        try {
            if (Citrine.isImportingEvents) {
                Log.d(Citrine.TAG, "Duplicate tag cleanup deferred: import in progress")
                return
            }

            val daos = listOf(
                database.eventDao(),
                HistoryDatabase.getDatabase(context).eventDao(),
            )

            var totalEventsRepaired = 0
            var totalRowsRemoved = 0

            for (dao in daos) {
                val (events, rows) = cleanDao(dao) ?: return
                totalEventsRepaired += events
                totalRowsRemoved += rows
            }

            Settings.duplicateTagsCleaned = true
            LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
            Log.d(
                Citrine.TAG,
                "Duplicate tag cleanup finished: repaired $totalEventsRepaired event(s), removed $totalRowsRemoved duplicate tag row(s)",
            )
        } finally {
            running.set(false)
        }
    }

    /**
     * Scans [dao] in two passes: priority replaceable kinds first, then all events by primary
     * key. Returns `(eventsRepaired, rowsRemoved)`, or `null` if deferred by an active import.
     */
    internal suspend fun cleanDao(dao: EventDao): Pair<Int, Int>? {
        val tally = RepairTally()

        for (kind in PRIORITY_KINDS) {
            if (!scanKind(dao, kind, tally)) return null
        }

        if (!scanAllEvents(dao, tally)) return null

        return tally.eventsRepaired to tally.rowsRemoved
    }

    private class RepairTally {
        var eventsRepaired = 0
        var rowsRemoved = 0
    }

    private suspend fun scanKind(dao: EventDao, kind: Int, tally: RepairTally): Boolean {
        var cursorCreatedAt = Long.MAX_VALUE
        var cursorId = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            if (Citrine.isImportingEvents) return false
            val page = dao.getIdsAndPubkeysByKindAfter(kind, cursorCreatedAt, cursorId, SCAN_PAGE_SIZE)
            if (page.isEmpty()) break
            repairAll(dao, dao.findEventIdsWithDuplicateTags(page.map { it.id }), tally)
            if (page.size < SCAN_PAGE_SIZE) break
            cursorCreatedAt = page.last().createdAt
            cursorId = page.last().id
            yield()
        }
        return true
    }

    private suspend fun scanAllEvents(dao: EventDao, tally: RepairTally): Boolean {
        var lastId = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            if (Citrine.isImportingEvents) return false
            val page = dao.getEventIdsAfter(lastId, SCAN_PAGE_SIZE)
            if (page.isEmpty()) break
            repairAll(dao, dao.findEventIdsWithDuplicateTags(page), tally)
            if (page.size < SCAN_PAGE_SIZE) break
            lastId = page.last()
            yield()
        }
        return true
    }

    private suspend fun repairAll(dao: EventDao, dupIds: List<String>, tally: RepairTally) {
        for (eventId in dupIds) {
            val removed = deduplicateEvent(dao, eventId)
            if (removed > 0) {
                tally.eventsRepaired++
                tally.rowsRemoved += removed
            }
        }
    }
}
