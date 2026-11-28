package com.greenart7c3.citrine.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.HistoryDatabase
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.service.EventBroadcastWorker
import com.greenart7c3.citrine.utils.isEphemeral
import com.greenart7c3.citrine.utils.isParameterizedReplaceable
import com.greenart7c3.citrine.utils.shouldDelete
import com.greenart7c3.citrine.utils.shouldOverwrite
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.tags.aTag.taggedAddresses
import com.vitorpamplona.quartz.nip01Core.tags.events.taggedEvents
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private fun dTagOrEmpty(event: Event): String = event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: ""

private fun kindAndBanListsAllow(event: Event): Boolean {
    if (event.kind in Settings.rejectedKinds) {
        return false
    }
    if (Settings.bannedPubKeys.containsKey(event.pubKey)) return false
    if (Settings.bannedEventIds.containsKey(event.id)) return false
    if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
        return false
    }
    return true
}

private fun pubkeysAllowed(event: Event): Boolean {
    if (Settings.allowedTaggedPubKeys.isEmpty() && Settings.allowedPubKeys.isEmpty()) {
        return true
    }
    val taggedUsers = event.taggedUsers()
    if (Settings.allowedTaggedPubKeys.isNotEmpty() &&
        taggedUsers.isNotEmpty() &&
        taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }
    ) {
        if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
            return false
        }
    }
    if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
        if (Settings.allowedTaggedPubKeys.isEmpty() ||
            taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }
        ) {
            return false
        }
    }
    return true
}

private fun policyAllows(event: Event): Boolean = kindAndBanListsAllow(event) && pubkeysAllowed(event)

internal class EventStore(
    private val appDatabase: AppDatabase,
    private val context: Context,
    private val onEphemeralEventStored: () -> Unit,
) {
    @Volatile private var cachedIsOffline: Boolean = false

    @Volatile private var cachedIsOfflineAt: Long = 0L

    suspend fun store(event: Event, connection: Connection?) {
        when {
            event.shouldDelete() -> {
                deleteEvent(event, connection)
            }
            event.isParameterizedReplaceable() -> {
                handleParameterizedReplaceable(event, connection)
            }
            event.shouldOverwrite() -> {
                override(event, connection)
            }
            else -> {
                save(event, connection)
            }
        }
    }

    suspend fun storeBatch(events: List<Event>) {
        if (events.isEmpty()) return

        val filtered = events
            .asSequence()
            .filter { !it.isExpired() }
            .filter { policyAllows(it) }
            .distinctBy { it.id }
            .toList()
        if (filtered.isEmpty()) return

        val verified = verifyInParallel(filtered)
        if (verified.isEmpty()) return

        val accepted = notStoredOrDeleted(verified)

        val (replaceable, nonReplaceable) = accepted.partition {
            it.isParameterizedReplaceable() || it.shouldOverwrite()
        }
        val collapsedReplaceables = replaceable
            .groupBy { Triple(it.pubKey, it.kind, dTagOrEmpty(it)) }
            .values
            .map { group -> group.maxByOrNull { it.createdAt }!! }

        val (deletes, inserts) = nonReplaceable.partition { it.shouldDelete() }

        appDatabase.withTransaction {
            val finalReplaceables = collapsedReplaceables.filter { e -> isNewestReplaceable(e) }

            val toStore = inserts + finalReplaceables + deletes
            if (toStore.isNotEmpty()) {
                appDatabase.eventDao()
                    .insertEventsWithTagsBatch(toStore.map { it.toEventWithTags() })
            }

            finalReplaceables.forEach { e -> deleteOlderReplaceables(e) }

            deletes.forEach { applyDeleteTagsOwnedBy(it) }
        }
    }

    private suspend fun verifyInParallel(filtered: List<Event>): List<Event> = withContext(Dispatchers.Default) {
        val sem = Semaphore(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        filtered.map { e ->
            async { sem.withPermit { if (e.verify()) e else null } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun notStoredOrDeleted(verified: List<Event>): List<Event> {
        val ids = verified.map { it.id }
        val existing = ids.chunked(500)
            .flatMap { appDatabase.eventDao().existingIds(it) }
            .toHashSet()
        val deletedByE = ids.chunked(500)
            .flatMap { appDatabase.eventDao().getDeletedEventsByIds(it) }
            .toHashSet()

        val candidates = verified.filter {
            it.id !in existing && it.id !in deletedByE && !it.isEphemeral()
        }

        val addressableDeleted = HashSet<String>()
        candidates.forEach { e ->
            if (e is AddressableEvent) {
                val deletions = appDatabase.eventDao().getDeletedEventsByATag(e.address().toValue())
                if (deletions.any { deletedAt -> deletedAt >= e.createdAt }) {
                    addressableDeleted.add(e.id)
                }
            }
        }

        return candidates.filter { it.id !in addressableDeleted }
    }

    private suspend fun isNewestReplaceable(e: Event): Boolean = if (e.isParameterizedReplaceable()) {
        appDatabase.eventDao()
            .getNewestReplaceable(e.kind, e.pubKey, dTagOrEmpty(e), e.createdAt, e.id)
            .isEmpty()
    } else {
        appDatabase.eventDao()
            .getByKindNewest(e.kind, e.pubKey, e.createdAt, e.id)
            .isEmpty()
    }

    private suspend fun deleteOlderReplaceables(e: Event) {
        if (e.isParameterizedReplaceable()) {
            val old = appDatabase.eventDao()
                .getOldestReplaceable(e.kind, e.pubKey, dTagOrEmpty(e))
            if (old.isNotEmpty()) appDatabase.eventDao().delete(old, e.pubKey)
        } else {
            val old = appDatabase.eventDao().getByKind(e.kind, e.pubKey).drop(1)
            if (old.isNotEmpty()) appDatabase.eventDao().delete(old, e.pubKey)
        }
    }

    private suspend fun handleParameterizedReplaceable(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getOldestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "")
        appDatabase.eventDao().delete(ids, event.pubKey)
        val history = HistoryDatabase.getDatabase(context)
        history.eventDao().insertEventWithTags(history, event.toEventWithTags(), connection = connection)
    }

    private suspend fun override(event: Event, connection: Connection?) {
        save(event, connection)
        val ids = appDatabase.eventDao().getByKind(event.kind, event.pubKey).drop(1)
        if (ids.isEmpty()) return
        appDatabase.eventDao().delete(ids, event.pubKey)
        val history = HistoryDatabase.getDatabase(context)
        history.eventDao().insertEventWithTags(history, event.toEventWithTags(), connection = connection)
    }

    private fun isOffline(): Boolean {
        val now = System.currentTimeMillis()
        if (now - cachedIsOfflineAt < 5_000L) return cachedIsOffline

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val offline = if (connectivityManager == null) {
            true
        } else {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        cachedIsOffline = offline
        cachedIsOfflineAt = now
        return offline
    }

    private fun broadcastWithWorkManager(eventId: String) {
        try {
            val inputData = androidx.work.Data.Builder()
                .putString("event_id", eventId)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<EventBroadcastWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(Citrine.TAG, "Queued event $eventId for broadcast when connectivity is restored")
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Failed to queue event for broadcast: ${e.message}", e)
        }
    }

    private suspend fun save(event: Event, connection: Connection?) {
        appDatabase.eventDao().insertEventWithTags(appDatabase, event.toEventWithTags(), connection = connection)

        if (event.isEphemeral()) {
            onEphemeralEventStored()
        }

        // Check if offline and broadcast with WorkManager
        if (isOffline() && appDatabase.eventDao().existsById(event.id)) {
            broadcastWithWorkManager(event.id)
        }
    }

    private suspend fun deleteEvent(event: Event, connection: Connection?) {
        save(event, connection)
        applyDeleteTagsOwnedBy(event)
    }

    private suspend fun applyDeleteTagsOwnedBy(event: Event) {
        val taggedEventIds = event.taggedEvents().map { it.eventId }
        if (taggedEventIds.isNotEmpty()) {
            appDatabase.eventDao().delete(taggedEventIds, event.pubKey)
        }
        val taggedAddresses = event.taggedAddresses()
        if (taggedAddresses.isEmpty()) return

        taggedAddresses.forEach { aTag ->
            val events = EventRepository.query(
                appDatabase,
                EventFilter(
                    authors = setOf(aTag.pubKeyHex),
                    kinds = setOf(aTag.kind),
                    tags = mapOf("d" to setOf(aTag.dTag)),
                    until = event.createdAt.toInt(),
                ),
            )

            appDatabase.eventDao().delete(events.map { it.event.id }, event.pubKey)
        }
    }
}
