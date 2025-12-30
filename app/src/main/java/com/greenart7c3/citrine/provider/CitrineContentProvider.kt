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
            EVENTS -> queryAllEvents(uri, projection, selection, selectionArgs, sortOrder)
            EVENT_ID -> {
                val eventId = uri.lastPathSegment
                queryEventById(eventId, projection)
            }
            EVENTS_BY_PUBKEY -> queryEventsByPubkey(uri, projection, sortOrder)
            EVENTS_BY_KIND -> queryEventsByKind(uri, projection, sortOrder)
            TAGS -> queryAllTags(uri, projection, selection, selectionArgs, sortOrder)
            TAGS_BY_EVENT -> queryTagsByEvent(uri, projection, sortOrder)
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun queryAllEvents(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
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

            // Apply date filtering if provided
            events.filter { event ->
                (createdAtFrom == null || event.event.createdAt >= createdAtFrom) &&
                    (createdAtTo == null || event.event.createdAt <= createdAtTo)
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

    private fun queryEventById(eventId: String?, projection: Array<out String>?): Cursor? {
        if (eventId == null) return null

        val event = runBlocking {
            eventDao.getById(eventId)
        }

        return if (event != null) {
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

        val kind = uri.getQueryParameter(CitrineContract.Events.PARAM_KIND)?.toIntOrNull()
        val limit = uri.getQueryParameter(CitrineContract.Events.PARAM_LIMIT)?.toIntOrNull()

        val events = runBlocking {
            if (kind != null) {
                val ids = eventDao.getByKind(kind, pubkey)
                eventDao.getByIds(ids)
            } else {
                // Get all events for this pubkey
                val allIds = eventDao.getAllIds()
                val allEvents = eventDao.getByIds(allIds)
                allEvents.filter { it.event.pubkey == pubkey }
            }
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

        val pubkey = uri.getQueryParameter(CitrineContract.Events.PARAM_PUBKEY)
        val limit = uri.getQueryParameter(CitrineContract.Events.PARAM_LIMIT)?.toIntOrNull()

        val events = runBlocking {
            if (pubkey != null) {
                val ids = eventDao.getByKind(kind, pubkey)
                eventDao.getByIds(ids)
            } else {
                // Get all events of this kind
                val allIds = eventDao.getAllIds()
                val allEvents = eventDao.getByIds(allIds)
                allEvents.filter { it.event.kind == kind }
            }
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
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // Tags are typically queried with events, so this returns empty for now
        // Can be implemented if needed
        return buildTagCursor(emptyList(), projection)
    }

    private fun queryTagsByEvent(
        uri: Uri,
        projection: Array<out String>?,
        sortOrder: String?,
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

        val event = runBlocking {
            // Check if event already exists
            val existing = eventDao.getById(eventId)
            if (existing != null) {
                // Event already exists, return existing URI
                return@runBlocking existing.event.id
            }

            // Create new event entity
            val eventEntity = com.greenart7c3.citrine.database.EventEntity(
                id = eventId,
                pubkey = values.getAsString(CitrineContract.Events.COLUMN_PUBKEY)
                    ?: throw IllegalArgumentException("Pubkey is required"),
                createdAt = values.getAsLong(CitrineContract.Events.COLUMN_CREATED_AT)
                    ?: throw IllegalArgumentException("CreatedAt is required"),
                kind = values.getAsInteger(CitrineContract.Events.COLUMN_KIND)
                    ?: throw IllegalArgumentException("Kind is required"),
                content = values.getAsString(CitrineContract.Events.COLUMN_CONTENT) ?: "",
                sig = values.getAsString(CitrineContract.Events.COLUMN_SIG)
                    ?: throw IllegalArgumentException("Signature is required"),
            )

            // Insert event (tags would need to be handled separately if provided)
            eventDao.insertEvent(eventEntity)
            eventId
        }

        return Uri.withAppendedPath(CitrineContract.Events.CONTENT_URI, event)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val matchCode = uriMatcher.match(uri)
        Log.d(TAG, "Delete URI: $uri, match code: $matchCode")

        return when (matchCode) {
            EVENT_ID -> {
                val eventId = uri.lastPathSegment
                deleteEventById(eventId)
            }
            else -> throw IllegalArgumentException("Delete not supported for URI: $uri")
        }
    }

    private fun deleteEventById(eventId: String?): Int {
        if (eventId == null) return 0

        return runBlocking {
            // Use the delete method that takes a list of IDs
            // This will delete the event and cascade delete tags
            eventDao.delete(listOf(eventId))
            1 // Return 1 if deletion was attempted (actual count would require checking)
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
