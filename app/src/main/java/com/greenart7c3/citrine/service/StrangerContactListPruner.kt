package com.greenart7c3.citrine.service

import android.content.Context
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.HistoryDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.Settings
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import kotlinx.coroutines.yield

private const val TAG = "RelayAggregator"

// Rows deleted per statement by the one-shot contact-list cleanup. Small enough that no
// single transaction holds the database long enough to stall the relay's writers.
private const val CONTACT_LIST_PRUNE_CHUNK_SIZE = 200

/**
 * Delete every kind-3 event whose author is neither the owner, followed by the owner, nor
 * explicitly protected by the relay settings. Runs once per install (guarded by
 * [Settings.strangerContactListsPruned]) over both the main and the history database; the
 * history database holds the replaced versions of the same contact lists.
 *
 * Deleted events can be re-fetched from the network if the owner ever follows the author.
 * This database runs with `auto_vacuum=FULL`, so each chunk also rewrites pages to reclaim
 * disk space: the delete is slow and the file shrinks as rows go (measured on a 1.26 GB
 * database: 4,227 contact lists deleted in ~4.5 min, file down to 337 MB), which is why the
 * chunks are small and the loop yields between them.
 */
/**
 * For an author we just saw an event from but who has no profile / contact list / NIP-65
 * relay list in the local DB, pull whatever's missing from the indexer relays once and
 * persist it. The kind-10002 list is fetched first so that, if we just learned it, we can
 * route the kind-0 fetch to the user's own write relays instead of the indexers. No-op
 * when everything is already cached. Caller is responsible for deduping repeated
 * invocations via [backfilledPubkeys].
 */
internal suspend fun pruneStrangerContactLists(
    context: Context,
    database: AppDatabase,
    aggregatorPubkey: String,
    ownerFollows: Set<String>,
) {
    if (Settings.strangerContactListsPruned) return
    if (Citrine.isImportingEvents) {
        Log.d(TAG, "Contact list cleanup skipped: import in progress")
        return
    }
    if (ownerFollows.isEmpty()) {
        Log.d(TAG, "Contact list cleanup skipped: owner's follow list not resolved this refresh")
        return
    }

    val keep = ContactListPolicy.keepSet(
        aggregatorPubkey = aggregatorPubkey,
        ownerFollows = ownerFollows,
        relayOwnerPubkey = Settings.ownerPubkey,
        neverDeleteFrom = Settings.neverDeleteFrom,
        allowedPubKeys = Settings.allowedPubKeys,
    )

    val daos = listOf(
        database.eventDao(),
        HistoryDatabase.getDatabase(context).eventDao(),
    )

    var deleted = 0
    for (dao in daos) {
        // Paged so a huge database never holds every kind-3 row in the heap at once. Deleting
        // rows already behind the cursor does not disturb the keyset.
        var cursorCreatedAt = Long.MAX_VALUE
        var cursorId = ""
        while (true) {
            val page = dao.getIdsAndPubkeysByKindAfter(ContactListEvent.KIND, cursorCreatedAt, cursorId, CONTACT_LIST_PRUNE_CHUNK_SIZE)
            if (page.isEmpty()) break
            val ids = ContactListPolicy.strangerContactListIds(page, ownerFollows, keep)
            if (ids.isNotEmpty()) {
                dao.delete(ids)
                deleted += ids.size
            }
            cursorCreatedAt = page.last().createdAt
            cursorId = page.last().id
            // Give writers (and the UI) a chance between chunks on a multi-thousand row delete.
            yield()
        }
    }

    Settings.strangerContactListsPruned = true
    LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
    Log.d(TAG, "Contact list cleanup: deleted $deleted kind-3 event(s) from authors outside the follow graph")
}
