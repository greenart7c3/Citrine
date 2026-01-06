package com.greenart7c3.citrine.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventDao
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.TagEntity
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.database.toEventWithTags
import com.greenart7c3.citrine.server.CustomWebSocketServer
import com.greenart7c3.citrine.server.Settings
import com.greenart7c3.citrine.service.CustomWebSocketService
import com.greenart7c3.citrine.utils.KINDS_PRIVATE_EVENTS
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.tags.people.taggedUsers
import com.vitorpamplona.quartz.utils.EventFactory
import kotlinx.coroutines.runBlocking

/**
 * Content Provider for exposing Citrine's Nostr event data to other Android apps.
 * Allows external apps to query events and tags using ContentResolver.
 */
class CitrineContentProvider : ContentProvider() {

    private lateinit var database: AppDatabase
    private lateinit var eventDao: EventDao

    companion object {
        private const val TAG = "CitrineContentProvider"

        // URI matcher codes
        private const val EVENTS = 100
        private const val EVENT_ID = 101
        private const val EVENTS_BY_PUBKEY = 102
        private const val EVENTS_BY_KIND = 103
        private const val TAGS = 200
        private const val TAGS_BY_EVENT = 201

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(CitrineContract.AUTHORITY, "events", EVENTS)
            addURI(CitrineContract.AUTHORITY, "events/#", EVENT_ID)
            addURI(CitrineContract.AUTHORITY, "events/by_pubkey", EVENTS_BY_PUBKEY)
            addURI(CitrineContract.AUTHORITY, "events/by_kind", EVENTS_BY_KIND)
            addURI(CitrineContract.AUTHORITY, "tags", TAGS)
            addURI(CitrineContract.AUTHORITY, "tags/by_event", TAGS_BY_EVENT)
        }
    }

    override fun onCreate(): Boolean {
        database = AppDatabase.getDatabase(context ?: return false)
        eventDao = database.eventDao()
        return true
    }

    /**
     * Filters events based on authentication when auth is enabled.
     * Returns true if the event should be included in results, false otherwise.
     * Matches the logic from SubscriptionManager.execute() lines 25-58.
     */
    private fun shouldIncludeEvent(eventWithTags: EventWithTags, authPubkey: String?): Boolean {
        if (!Settings.authEnabled) {
            return true
        }

        // If no auth pubkey provided, exclude private events and events with p tags/authors
        val authenticatedPubkey = authPubkey ?: return false

        val event = eventWithTags.toEvent()
        val eventKind = event.kind

        // Check if event is a private event kind
        if (KINDS_PRIVATE_EVENTS.contains(eventKind)) {
            // For private events, require auth and check if user is sender or receiver
            val senders = setOf(eventWithTags.event.pubkey)
            val receivers = event.taggedUsers().map { it.pubKey }.toSet()

            val hasAccess = senders.contains(authenticatedPubkey) || receivers.contains(authenticatedPubkey)

            if (!hasAccess) {
                Log.d(TAG, "Excluding private event ${event.id} - auth pubkey $authenticatedPubkey not authorized")
            }

            return hasAccess
        }

        // For non-private events, check if they have p tags or authors
        // This matches SubscriptionManager logic for empty kind filters with p tags or authors
        val hasPTags = eventWithTags.tags.any { it.col0Name == "p" }
        val hasAuthors = eventWithTags.event.pubkey.isNotEmpty()

        if (!hasPTags && !hasAuthors) {
            // No p tags and no specific authors, include it
            return true
        }

        // Has p tags or authors, check if authenticated user is sender or receiver
        val senders = setOf(eventWithTags.event.pubkey)
        val receivers = eventWithTags.tags
            .filter { it.col0Name == "p" }
            .mapNotNull { it.col1Value }
            .toSet()

        val hasAccess = senders.contains(authenticatedPubkey) || receivers.contains(authenticatedPubkey)

        if (!hasAccess) {
            Log.d(TAG, "Excluding event ${event.id} with p tags/authors - auth pubkey $authenticatedPubkey not authorized")
        }

        return hasAccess
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val matchCode = uriMatcher.match(uri)
        Log.d(TAG, "Query URI: $uri, match code: $matchCode")

        return when (matchCode) {
            EVENTS -> queryAllEvents(uri, projection)
            EVENT_ID -> {
                val eventId = uri.lastPathSegment
                queryEventById(eventId, projection, uri)
            }
            EVENTS_BY_PUBKEY -> queryEventsByPubkey(uri, projection, sortOrder)
            EVENTS_BY_KIND -> queryEventsByKind(uri, projection, sortOrder)
            TAGS -> queryAllTags(projection)
            TAGS_BY_EVENT -> queryTagsByEvent(uri, projection)
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun queryAllEvents(
        uri: Uri,
        projection: Array<out String>?,
    ): Cursor {
        val authPubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_AUTH_PUBKEY)
        val events = runBlocking {
            val limit = uri.getQueryParameter(CitrineContract.Events.PARAM_LIMIT)?.toIntOrNull()
            val offset = uri.getQueryParameter(CitrineContract.Events.PARAM_OFFSET)?.toIntOrNull() ?: 0
            val createdAtFrom = uri.getQueryParameter(CitrineContract.Events.PARAM_CREATED_AT_FROM)?.toLongOrNull()
            val createdAtTo = uri.getQueryParameter(CitrineContract.Events.PARAM_CREATED_AT_TO)?.toLongOrNull()

            // For now, get all events (can be optimized later with proper filtering)
            // This is a simplified implementation - in production, you'd want to add
            // proper filtering support in the DAO
            val allIds = eventDao.getAllIds()
            val events = eventDao.getByIds(allIds)

            // Apply date filtering and auth filtering if provided
            events.filter { event ->
                (createdAtFrom == null || event.event.createdAt >= createdAtFrom) &&
                    (createdAtTo == null || event.event.createdAt <= createdAtTo) &&
                    shouldIncludeEvent(event, authPubkey)
            }.let { filtered ->
                if (limit != null) {
                    filtered.drop(offset).take(limit)
                } else {
                    filtered.drop(offset)
                }
            }
        }

        return buildEventCursor(events, projection)
    }

    private fun queryEventById(eventId: String?, projection: Array<out String>?, uri: Uri? = null): Cursor? {
        if (eventId == null) return null

        val authPubkey = uri?.getQueryParameter(CitrineContract.Events.PARAM_AUTH_PUBKEY)

        val event = runBlocking {
            eventDao.getById(eventId)
        }

        return if (event != null && shouldIncludeEvent(event, authPubkey)) {
            buildEventCursor(listOf(event), projection)
        } else {
            buildEventCursor(emptyList(), projection)
        }
    }

    private fun queryEventsByPubkey(
        uri: Uri,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val pubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_PUBKEY)
            ?: return buildEventCursor(emptyList(), projection)

        val authPubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_AUTH_PUBKEY)
        val kind = uri.getQueryParameter(CitrineContract.Events.PARAM_KIND)?.toIntOrNull()
        val limit = uri.getQueryParameter(CitrineContract.Events.PARAM_LIMIT)?.toIntOrNull()

        val events = runBlocking {
            val rawEvents = if (kind != null) {
                val ids = eventDao.getByKind(kind, pubkey)
                eventDao.getByIds(ids)
            } else {
                // Get all events for this pubkey
                val allIds = eventDao.getAllIds()
                val allEvents = eventDao.getByIds(allIds)
                allEvents.filter { it.event.pubkey == pubkey }
            }
            // Apply auth filtering
            rawEvents.filter { shouldIncludeEvent(it, authPubkey) }
        }

        val sortedEvents = if (sortOrder != null) {
            when {
                sortOrder.contains("createdAt DESC") -> events.sortedByDescending { it.event.createdAt }
                sortOrder.contains("createdAt ASC") -> events.sortedBy { it.event.createdAt }
                else -> events
            }
        } else {
            events.sortedByDescending { it.event.createdAt }
        }

        val limitedEvents = if (limit != null) {
            sortedEvents.take(limit)
        } else {
            sortedEvents
        }

        return buildEventCursor(limitedEvents, projection)
    }

    private fun queryEventsByKind(
        uri: Uri,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val kind = uri.getQueryParameter(CitrineContract.Events.PARAM_KIND)?.toIntOrNull()
            ?: return buildEventCursor(emptyList(), projection)

        val authPubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_AUTH_PUBKEY)
        val pubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_PUBKEY)
        val limit = uri.getQueryParameter(CitrineContract.Events.PARAM_LIMIT)?.toIntOrNull()

        val events = runBlocking {
            val rawEvents = if (pubkey != null) {
                val ids = eventDao.getByKind(kind, pubkey)
                eventDao.getByIds(ids)
            } else {
                // Get all events of this kind
                val allIds = eventDao.getAllIds()
                val allEvents = eventDao.getByIds(allIds)
                allEvents.filter { it.event.kind == kind }
            }
            // Apply auth filtering
            rawEvents.filter { shouldIncludeEvent(it, authPubkey) }
        }

        val sortedEvents = if (sortOrder != null) {
            when {
                sortOrder.contains("createdAt DESC") -> events.sortedByDescending { it.event.createdAt }
                sortOrder.contains("createdAt ASC") -> events.sortedBy { it.event.createdAt }
                else -> events
            }
        } else {
            events.sortedByDescending { it.event.createdAt }
        }

        val limitedEvents = if (limit != null) {
            sortedEvents.take(limit)
        } else {
            sortedEvents
        }

        return buildEventCursor(limitedEvents, projection)
    }

    private fun queryAllTags(
        projection: Array<out String>?,
    ): Cursor {
        // Tags are typically queried with events, so this returns empty for now
        // Can be implemented if needed
        return buildTagCursor(emptyList(), projection)
    }

    private fun queryTagsByEvent(
        uri: Uri,
        projection: Array<out String>?,
    ): Cursor {
        val eventId = uri.getQueryParameter(CitrineContract.Tags.PARAM_EVENT_ID)
            ?: return buildTagCursor(emptyList(), projection)

        val event = runBlocking {
            eventDao.getById(eventId)
        }

        return if (event != null) {
            buildTagCursor(event.tags, projection)
        } else {
            buildTagCursor(emptyList(), projection)
        }
    }

    private fun buildEventCursor(
        events: List<EventWithTags>,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: arrayOf(
            CitrineContract.Events.COLUMN_ID,
            CitrineContract.Events.COLUMN_PUBKEY,
            CitrineContract.Events.COLUMN_CREATED_AT,
            CitrineContract.Events.COLUMN_KIND,
            CitrineContract.Events.COLUMN_CONTENT,
            CitrineContract.Events.COLUMN_SIG,
        )

        val cursor = MatrixCursor(columns, events.size)

        events.forEach { eventWithTags ->
            val row = arrayOfNulls<Any?>(columns.size)
            columns.forEachIndexed { index, column ->
                row[index] = when (column) {
                    CitrineContract.Events.COLUMN_ID -> eventWithTags.event.id
                    CitrineContract.Events.COLUMN_PUBKEY -> eventWithTags.event.pubkey
                    CitrineContract.Events.COLUMN_CREATED_AT -> eventWithTags.event.createdAt
                    CitrineContract.Events.COLUMN_KIND -> eventWithTags.event.kind
                    CitrineContract.Events.COLUMN_CONTENT -> eventWithTags.event.content
                    CitrineContract.Events.COLUMN_SIG -> eventWithTags.event.sig
                    else -> null
                }
            }
            cursor.addRow(row)
        }

        return cursor
    }

    private fun buildTagCursor(
        tags: List<TagEntity>,
        projection: Array<out String>?,
    ): Cursor {
        val columns = projection ?: arrayOf(
            CitrineContract.Tags.COLUMN_PK,
            CitrineContract.Tags.COLUMN_PK_EVENT,
            CitrineContract.Tags.COLUMN_POSITION,
            CitrineContract.Tags.COLUMN_COL0_NAME,
            CitrineContract.Tags.COLUMN_COL1_VALUE,
            CitrineContract.Tags.COLUMN_COL2_DIFFERENTIATOR,
            CitrineContract.Tags.COLUMN_COL3_AMOUNT,
            CitrineContract.Tags.COLUMN_COL4_PLUS,
            CitrineContract.Tags.COLUMN_KIND,
        )

        val cursor = MatrixCursor(columns, tags.size)

        tags.forEach { tag ->
            val row = arrayOfNulls<Any?>(columns.size)
            columns.forEachIndexed { index, column ->
                row[index] = when (column) {
                    CitrineContract.Tags.COLUMN_PK -> tag.pk
                    CitrineContract.Tags.COLUMN_PK_EVENT -> tag.pkEvent
                    CitrineContract.Tags.COLUMN_POSITION -> tag.position
                    CitrineContract.Tags.COLUMN_COL0_NAME -> tag.col0Name
                    CitrineContract.Tags.COLUMN_COL1_VALUE -> tag.col1Value
                    CitrineContract.Tags.COLUMN_COL2_DIFFERENTIATOR -> tag.col2Differentiator
                    CitrineContract.Tags.COLUMN_COL3_AMOUNT -> tag.col3Amount
                    CitrineContract.Tags.COLUMN_COL4_PLUS -> tag.col4Plus.joinToString(",")
                    CitrineContract.Tags.COLUMN_KIND -> tag.kind
                    else -> null
                }
            }
            cursor.addRow(row)
        }

        return cursor
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        EVENTS, EVENTS_BY_PUBKEY, EVENTS_BY_KIND -> CitrineContract.Events.CONTENT_TYPE
        EVENT_ID -> CitrineContract.Events.CONTENT_ITEM_TYPE
        TAGS, TAGS_BY_EVENT -> CitrineContract.Tags.CONTENT_TYPE
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val matchCode = uriMatcher.match(uri)
        Log.d(TAG, "Insert URI: $uri, match code: $matchCode")

        return when (matchCode) {
            EVENTS -> insertEvent(values)
            else -> throw IllegalArgumentException("Insert not supported for URI: $uri")
        }
    }

    private fun insertEvent(values: ContentValues?): Uri? {
        if (values == null) return null

        val eventId = values.getAsString(CitrineContract.Events.COLUMN_ID)
            ?: throw IllegalArgumentException("Event ID is required")

        return runBlocking {
            // Extract event data from ContentValues
            val pubkey = values.getAsString(CitrineContract.Events.COLUMN_PUBKEY)
                ?: throw IllegalArgumentException("Pubkey is required")
            val createdAt = values.getAsLong(CitrineContract.Events.COLUMN_CREATED_AT)
                ?: throw IllegalArgumentException("CreatedAt is required")
            val kind = values.getAsInteger(CitrineContract.Events.COLUMN_KIND)
                ?: throw IllegalArgumentException("Kind is required")
            val content = values.getAsString(CitrineContract.Events.COLUMN_CONTENT) ?: ""
            val sig = values.getAsString(CitrineContract.Events.COLUMN_SIG)
                ?: throw IllegalArgumentException("Signature is required")

            // Extract tags from ContentValues if provided
            // Tags would be provided as a separate ContentValues or JSON string
            // For now, we'll create event without tags and let validation handle it
            val tags = emptyArray<Array<String>>()

            // Create Event object
            val event: Event = EventFactory.create(
                id = eventId,
                pubKey = pubkey,
                createdAt = createdAt,
                kind = kind,
                content = content,
                sig = sig,
                tags = tags,
            )

            // Validate and process event using the same logic as WebSocket server
            val server = CustomWebSocketService.server
            if (server != null) {
                val result = server.innerProcessEvent(event, null, shouldVerify = true)

                when (result) {
                    CustomWebSocketServer.VerificationResult.Valid -> {
                        // Event was successfully validated and inserted by innerProcessEvent
                        Uri.withAppendedPath(CitrineContract.Events.CONTENT_URI, eventId)
                    }
                    CustomWebSocketServer.VerificationResult.InvalidId -> {
                        throw SecurityException("Event ID hash verification failed")
                    }
                    CustomWebSocketServer.VerificationResult.InvalidSignature -> {
                        throw SecurityException("Event signature verification failed")
                    }
                    CustomWebSocketServer.VerificationResult.Expired -> {
                        throw IllegalArgumentException("Event expired")
                    }
                    CustomWebSocketServer.VerificationResult.KindNotAllowed -> {
                        throw SecurityException("Kind not allowed")
                    }
                    CustomWebSocketServer.VerificationResult.PubkeyNotAllowed -> {
                        throw SecurityException("Pubkey not allowed")
                    }
                    CustomWebSocketServer.VerificationResult.TaggedPubkeyNotAllowed -> {
                        throw SecurityException("Tagged pubkey not allowed")
                    }
                    CustomWebSocketServer.VerificationResult.Deleted -> {
                        throw IllegalArgumentException("Event deleted")
                    }
                    CustomWebSocketServer.VerificationResult.AlreadyInDatabase -> {
                        // Event already exists, return existing URI
                        Uri.withAppendedPath(CitrineContract.Events.CONTENT_URI, eventId)
                    }
                    CustomWebSocketServer.VerificationResult.TaggedPubKeyMismatch -> {
                        throw SecurityException("Tagged event pubkey mismatch")
                    }
                    CustomWebSocketServer.VerificationResult.NewestEventAlreadyInDatabase -> {
                        throw IllegalArgumentException("Newest event already in database")
                    }
                    CustomWebSocketServer.VerificationResult.AuthRequiredForProtectedEvent -> {
                        throw SecurityException("Authentication required for protected event")
                    }
                }
            } else {
                // Server not available, do basic validation and insert
                if (!event.verify()) {
                    throw SecurityException("Event ID hash or signature verification failed")
                }

                // Check if event already exists
                val existing = eventDao.getById(eventId)
                if (existing != null) {
                    return@runBlocking Uri.withAppendedPath(CitrineContract.Events.CONTENT_URI, eventId)
                }

                val eventWithTags = event.toEventWithTags()
                eventDao.insertEventWithTags(eventWithTags, null, sendEventToSubscriptions = false)
                Uri.withAppendedPath(CitrineContract.Events.CONTENT_URI, eventId)
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val matchCode = uriMatcher.match(uri)
        Log.d(TAG, "Delete URI: $uri, match code: $matchCode")

        return when (matchCode) {
            EVENT_ID -> {
                val eventId = uri.lastPathSegment
                val authPubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_AUTH_PUBKEY)
                deleteEventById(eventId, authPubkey)
            }
            else -> throw IllegalArgumentException("Delete not supported for URI: $uri")
        }
    }

    private fun deleteEventById(eventId: String?, authPubkey: String?): Int {
        if (eventId == null) return 0

        return runBlocking {
            // Load event from database
            val eventWithTags = eventDao.getById(eventId)
            if (eventWithTags == null) {
                Log.d(TAG, "Event not found: $eventId")
                return@runBlocking 0
            }

            // Validate that auth pubkey matches event author when auth is enabled
            if (Settings.authEnabled) {
                if (authPubkey == null) {
                    Log.d(TAG, "Delete denied: no auth pubkey provided")
                    throw SecurityException("Authentication required to delete events")
                }

                if (authPubkey != eventWithTags.event.pubkey) {
                    Log.d(TAG, "Delete denied: auth pubkey $authPubkey does not match event author ${eventWithTags.event.pubkey}")
                    throw SecurityException("Only the event author can delete this event")
                }
            }

            // If server is available, validate using server logic
            val server = CustomWebSocketService.server
            if (server != null) {
                val event = eventWithTags.toEvent()
                // For delete events (kind 5), we need to validate the delete event itself
                // For regular events, we just check authorization
                when (val result = server.verifyEvent(event, null, shouldVerify = true)) {
                    CustomWebSocketServer.VerificationResult.Valid,
                    CustomWebSocketServer.VerificationResult.AlreadyInDatabase,
                    -> {
                        // Event is valid, proceed with deletion
                        eventDao.delete(listOf(eventId))
                        1
                    }
                    CustomWebSocketServer.VerificationResult.InvalidId -> {
                        throw SecurityException("Event ID hash verification failed")
                    }
                    CustomWebSocketServer.VerificationResult.Deleted -> {
                        // Already deleted
                        return@runBlocking 0
                    }
                    else -> {
                        throw SecurityException("Event validation failed: $result")
                    }
                }
            } else {
                // Server not available, just delete if authorized
                eventDao.delete(listOf(eventId))
                1
            }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        // Nostr events are generally immutable, so updates are not typically supported
        // This could be used for metadata updates if needed
        throw UnsupportedOperationException("Update not supported for Nostr events")
    }
}
