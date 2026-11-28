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

private const val CONTACT_LIST_PRUNE_CHUNK_SIZE = 200

private suspend fun letWritersRunBetweenChunks() = yield()

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
            letWritersRunBetweenChunks()
        }
    }

    Settings.strangerContactListsPruned = true
    LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)
    Log.d(TAG, "Contact list cleanup: deleted $deleted kind-3 event(s) from authors outside the follow graph")
}
