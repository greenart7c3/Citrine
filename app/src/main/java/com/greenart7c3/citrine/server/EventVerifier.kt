package com.greenart7c3.citrine.server

import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.CustomWebSocketServer.VerificationResult
import com.greenart7c3.citrine.server.nip29.GroupManager
import com.greenart7c3.citrine.utils.isEphemeral
import com.greenart7c3.citrine.utils.isParameterizedReplaceable
import com.greenart7c3.citrine.utils.shouldDelete
import com.greenart7c3.citrine.utils.shouldOverwrite
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.tags.aTag.taggedAddresses
import com.vitorpamplona.quartz.nip01Core.tags.events.taggedEvents
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.nip18Reposts.GenericRepostEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import com.vitorpamplona.quartz.nip70ProtectedEvts.isProtected

internal class EventVerifier(private val appDatabase: AppDatabase) {
    suspend fun verify(event: Event, connection: Connection?, shouldVerify: Boolean, fromAggregator: Boolean): VerificationResult {
        if (!shouldVerify) {
            return VerificationResult.Valid
        }

        val debugLog = Log.isLoggable(Citrine.TAG, Log.DEBUG)

        return policyRefusal(event, connection, fromAggregator, debugLog)
            ?: protectionRefusal(event, connection, debugLog)
            ?: deletionRefusal(event, debugLog)
            ?: storedRefusal(event, debugLog)
            ?: VerificationResult.Valid
    }

    private fun policyRefusal(event: Event, connection: Connection?, fromAggregator: Boolean, debugLog: Boolean): VerificationResult? = expiredRefusal(event, connection, debugLog)
        ?: sizeRefusal(event, debugLog)
        ?: kindRefusal(event, debugLog)
        ?: banRefusal(event, debugLog)
        ?: allowlistRefusal(event, fromAggregator, debugLog)

    private fun protectionRefusal(event: Event, connection: Connection?, debugLog: Boolean): VerificationResult? = protectedEventRefusal(event, connection, debugLog)
        ?: repostRefusal(event, debugLog)
        ?: signatureRefusal(event, debugLog)

    private suspend fun deletionRefusal(event: Event, debugLog: Boolean): VerificationResult? = deletedByAuthorRefusal(event, debugLog)
        ?: moderationRefusal(event, debugLog)
        ?: addressDeletedRefusal(event, debugLog)

    private suspend fun storedRefusal(event: Event, debugLog: Boolean): VerificationResult? = when {
        event.shouldDelete() -> deletionRequestRefusal(event, debugLog)
        event.isParameterizedReplaceable() -> addressableStoredRefusal(event, debugLog)
        event.shouldOverwrite() -> replaceableStoredRefusal(event, debugLog)
        else -> regularStoredRefusal(event, debugLog)
    }

    private fun expiredRefusal(event: Event, connection: Connection?, debugLog: Boolean): VerificationResult? {
        if (event.isExpired() && connection != null) {
            if (debugLog) Log.d(Citrine.TAG, "event expired ${event.id} ${event.expiration()}")
            return VerificationResult.Expired
        }
        return null
    }

    private fun sizeRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        if (Settings.maxEventSize > 0 && event.content.length > Settings.maxEventSize) {
            if (debugLog) Log.d(Citrine.TAG, "event too large ${event.id} ${event.content.length}")
            return VerificationResult.EventTooLarge
        }
        return null
    }

    private fun kindRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        if (event.kind in Settings.rejectedKinds) {
            if (debugLog) Log.d(Citrine.TAG, "kind rejected ${event.kind}")
            return VerificationResult.KindRejected
        }

        if (Settings.allowedKinds.isNotEmpty() && event.kind !in Settings.allowedKinds) {
            if (debugLog) Log.d(Citrine.TAG, "kind not allowed ${event.kind}")
            return VerificationResult.KindNotAllowed
        }
        return null
    }

    private fun banRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        // NIP-86 ban lists — apply unconditionally (even to aggregator-fetched events),
        // so a banned author can't sneak in via the relay aggregator.
        if (Settings.bannedPubKeys.containsKey(event.pubKey)) {
            if (debugLog) Log.d(Citrine.TAG, "pubkey banned ${event.pubKey}")
            return VerificationResult.BannedPubkey
        }
        if (Settings.bannedEventIds.containsKey(event.id)) {
            if (debugLog) Log.d(Citrine.TAG, "event id banned ${event.id}")
            return VerificationResult.BannedEvent
        }
        return null
    }

    private fun allowlistRefusal(event: Event, fromAggregator: Boolean, debugLog: Boolean): VerificationResult? {
        // Aggregator-fetched events bypass the author/tagged-pubkey allowlists
        // because the aggregator already validated them against the user's
        // requested subscription filter before handing them off here.
        if (!fromAggregator && (Settings.allowedTaggedPubKeys.isNotEmpty() || Settings.allowedPubKeys.isNotEmpty())) {
            val taggedUsers = event.taggedUsers()
            return taggedPubkeyRefusal(event, taggedUsers, debugLog) ?: authorRefusal(event, taggedUsers, debugLog)
        }
        return null
    }

    private fun taggedPubkeyRefusal(event: Event, taggedUsers: List<PTag>, debugLog: Boolean): VerificationResult? {
        if (Settings.allowedTaggedPubKeys.isNotEmpty() && taggedUsers.isNotEmpty() && taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }) {
            if (Settings.allowedPubKeys.isEmpty() || (event.pubKey !in Settings.allowedPubKeys)) {
                if (debugLog) Log.d(Citrine.TAG, "tagged pubkey not allowed ${event.id}")
                return VerificationResult.TaggedPubkeyNotAllowed
            }
        }
        return null
    }

    private fun authorRefusal(event: Event, taggedUsers: List<PTag>, debugLog: Boolean): VerificationResult? {
        if (Settings.allowedPubKeys.isNotEmpty() && event.pubKey !in Settings.allowedPubKeys) {
            if (Settings.allowedTaggedPubKeys.isEmpty() || taggedUsers.none { it.pubKey in Settings.allowedTaggedPubKeys }) {
                if (debugLog) Log.d(Citrine.TAG, "pubkey not allowed ${event.id}")
                return VerificationResult.PubkeyNotAllowed
            }
        }
        return null
    }

    private fun protectedEventRefusal(event: Event, connection: Connection?, debugLog: Boolean): VerificationResult? {
        if (event.isProtected() && connection != null) {
            val remoteHost = connection.session.call.request.local.remoteHost
            val isLocalHost = remoteHost in listOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")

            if (!isLocalHost && !connection.users.contains(event.pubKey)) {
                if (debugLog) Log.d(Citrine.TAG, "auth required for protected event ${event.id}")
                return VerificationResult.AuthRequiredForProtectedEvent
            }
        }
        return null
    }

    private fun repostRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        // NIP-70: reposts of protected events MUST NOT embed the reposted event.
        if (event.kind == RepostEvent.KIND || event.kind == GenericRepostEvent.KIND) {
            val embedded = try {
                Event.fromJson(event.content)
            } catch (_: Exception) {
                null
            }
            if (embedded != null && embedded.isProtected()) {
                if (debugLog) Log.d(Citrine.TAG, "rejecting repost ${event.id} that embeds protected event ${embedded.id}")
                return VerificationResult.ProtectedEventEmbeddedInRepost
            }
        }
        return null
    }

    private fun signatureRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        // Verify the signature before any DB access so invalid events are
        // rejected without paying for the reads below.
        if (!event.verify()) {
            if (debugLog) Log.d(Citrine.TAG, "event ${event.id} does not have a valid id or signature")
            return VerificationResult.InvalidId
        }
        return null
    }

    private suspend fun deletedByAuthorRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        val deletedEvents = appDatabase.eventDao().getDeletedEvents(event.id)
        if (deletedEvents.isNotEmpty()) {
            if (debugLog) Log.d(Citrine.TAG, "Event deleted ${event.id}")
            return VerificationResult.Deleted
        }
        return null
    }

    private suspend fun moderationRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        // NIP-29: ids removed by a group admin's kind-9005 moderation event stay deleted,
        // but only for groups this relay manages — mirrored 9005s from foreign groups
        // must not block re-imports.
        if (GroupManager.hasGroups()) {
            val moderationDeleted = appDatabase.eventDao().getModerationDeletedGroups(event.id)
            if (moderationDeleted.any { GroupManager.get(it) != null }) {
                if (debugLog) Log.d(Citrine.TAG, "Event deleted by group moderation ${event.id}")
                return VerificationResult.Deleted
            }
        }
        return null
    }

    private suspend fun addressDeletedRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        if (event is AddressableEvent) {
            val events = appDatabase.eventDao().getDeletedEventsByATag(event.address().toValue())
            events.forEach { deletedAt ->
                if (deletedAt >= event.createdAt) {
                    if (debugLog) Log.d(Citrine.TAG, "Event deleted ${event.id}")
                    return VerificationResult.Deleted
                }
            }
        }
        return null
    }

    private suspend fun alreadyStoredRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        if (appDatabase.eventDao().existsById(event.id)) {
            if (debugLog) Log.d(Citrine.TAG, "Event already in database ${event.id}")
            return VerificationResult.AlreadyInDatabase
        }
        return null
    }

    private suspend fun deletionRequestRefusal(event: Event, debugLog: Boolean): VerificationResult? = alreadyStoredRefusal(event, debugLog)
        ?: taggedEventOwnerRefusal(event, debugLog)
        ?: taggedAddressOwnerRefusal(event, debugLog)

    private suspend fun taggedEventOwnerRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        event.taggedEvents().forEach { taggedEvent ->
            val taggedPubkey = appDatabase.eventDao().getPubkeyById(taggedEvent.eventId)
            if (taggedPubkey != null && taggedPubkey != event.pubKey) {
                if (debugLog) Log.d(Citrine.TAG, "Tagged event pubkey mismatch ${event.id}")
                return VerificationResult.TaggedPubKeyMismatch
            }
        }
        return null
    }

    private suspend fun taggedAddressOwnerRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        event.taggedAddresses().forEach { aTag ->
            val taggedEvents = EventRepository.query(
                appDatabase,
                EventFilter(
                    authors = setOf(aTag.pubKeyHex),
                    kinds = setOf(aTag.kind),
                    tags = mapOf("d" to setOf(aTag.dTag)),
                    until = event.createdAt.toInt(),
                ),
            )
            taggedEvents.forEach {
                if (it.event.pubkey != event.pubKey) {
                    if (debugLog) Log.d(Citrine.TAG, "Tagged event pubkey mismatch ${event.id}")
                    return VerificationResult.TaggedPubKeyMismatch
                }
            }
        }
        return null
    }

    private suspend fun addressableStoredRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        alreadyStoredRefusal(event, debugLog)?.let { return it }
        val newest = appDatabase.eventDao().getNewestReplaceable(event.kind, event.pubKey, event.tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: "", event.createdAt, event.id)
        if (newest.isNotEmpty()) {
            if (debugLog) Log.d(Citrine.TAG, "newest event already in database ${event.id}")
            return VerificationResult.NewestEventAlreadyInDatabase
        }
        return null
    }

    private suspend fun replaceableStoredRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        alreadyStoredRefusal(event, debugLog)?.let { return it }
        val newest = appDatabase.eventDao().getByKindNewest(event.kind, event.pubKey, event.createdAt, event.id)
        if (newest.isNotEmpty()) {
            if (debugLog) Log.d(Citrine.TAG, "newest event already in database ${event.id}")
            return VerificationResult.NewestEventAlreadyInDatabase
        }
        return null
    }

    private suspend fun regularStoredRefusal(event: Event, debugLog: Boolean): VerificationResult? {
        if (!event.isEphemeral() && appDatabase.eventDao().existsById(event.id)) {
            if (debugLog) Log.d(Citrine.TAG, "Event already in database ${event.id}")
            return VerificationResult.AlreadyInDatabase
        }
        return null
    }
}
