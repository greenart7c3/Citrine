package com.greenart7c3.citrine.server

import android.content.ContentResolver
import android.database.Cursor
import androidx.core.net.toUri
import com.fasterxml.jackson.databind.ObjectMapper
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.provider.CitrineContract
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.contentProviderApiRoutes(resolver: ContentResolver, objectMapper: ObjectMapper) {
    val api = ContentProviderApi(resolver, objectMapper)

    // ContentProvider test endpoints (for Postman testing)
    get("/api/events") {
        api.respondEvents(call)
    }

    get("/api/events/by_pubkey") {
        api.respondEventsByPubkey(call)
    }

    get("/api/events/by_kind") {
        api.respondEventsByKind(call)
    }

    // HTTP endpoints for testing ContentProvider errors
    get("/api/test/errors/invalid_offset") {
        api.respondRawQuery(call, "content://${CitrineContract.AUTHORITY}/events?offset=-1")
    }

    get("/api/test/errors/invalid_limit") {
        api.respondRawQuery(call, "content://${CitrineContract.AUTHORITY}/events?limit=-1")
    }

    get("/api/test/errors/invalid_date_range") {
        api.respondRawQuery(call, "content://${CitrineContract.AUTHORITY}/events?createdAtFrom=1000&createdAtTo=500")
    }

    get("/api/test/errors/missing_pubkey") {
        api.respondRawQuery(call, "content://${CitrineContract.AUTHORITY}/events/by_pubkey")
    }

    get("/api/test/errors/missing_kind") {
        api.respondRawQuery(call, "content://${CitrineContract.AUTHORITY}/events/by_kind")
    }

    get("/api/test/errors/invalid_event_id") {
        api.respondRawQuery(call, "content://${CitrineContract.AUTHORITY}/events/invalid_event_id")
    }
}

private class ContentProviderApi(
    private val resolver: ContentResolver,
    private val objectMapper: ObjectMapper,
) {
    suspend fun respondEvents(call: ApplicationCall) {
        call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
        call.response.headers.appendIfAbsent("Content-Type", "application/json")

        try {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val createdAtFrom = call.request.queryParameters["createdAt_from"]?.toLongOrNull()
            val createdAtTo = call.request.queryParameters["createdAt_to"]?.toLongOrNull()

            val uriBuilder = "content://${CitrineContract.AUTHORITY}/events".toUri()
                .buildUpon()
                .appendQueryParameter("limit", limit.toString())
                .appendQueryParameter("offset", offset.toString())

            createdAtFrom?.let { uriBuilder.appendQueryParameter("createdAt_from", it.toString()) }
            createdAtTo?.let { uriBuilder.appendQueryParameter("createdAt_to", it.toString()) }

            val cursor = resolver.query(uriBuilder.build(), null, null, null, null)
            val result = cursorToJson(cursor)

            call.respondText(result, ContentType.Application.Json)
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Error querying events", e)
            call.respondText(
                """{"error": "${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    suspend fun respondEventsByPubkey(call: ApplicationCall) {
        call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
        call.response.headers.appendIfAbsent("Content-Type", "application/json")

        try {
            val pubkey = call.request.queryParameters["pubkey"]
            if (pubkey == null) {
                call.respondText(
                    """{"error": "pubkey parameter required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }

            val kind = call.request.queryParameters["kind"]?.toIntOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

            val uriBuilder = "content://${CitrineContract.AUTHORITY}/events/by_pubkey".toUri()
                .buildUpon()
                .appendQueryParameter("pubkey", pubkey)
                .appendQueryParameter("limit", limit.toString())
                .appendQueryParameter("offset", offset.toString())

            kind?.let { uriBuilder.appendQueryParameter("kind", it.toString()) }

            val cursor = resolver.query(uriBuilder.build(), null, null, null, null)
            val result = cursorToJson(cursor)

            call.respondText(result, ContentType.Application.Json)
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Error querying events by pubkey", e)
            call.respondText(
                """{"error": "${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    suspend fun respondEventsByKind(call: ApplicationCall) {
        call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
        call.response.headers.appendIfAbsent("Content-Type", "application/json")

        try {
            val kind = call.request.queryParameters["kind"]?.toIntOrNull()
            if (kind == null) {
                call.respondText(
                    """{"error": "kind parameter required"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return
            }

            val pubkey = call.request.queryParameters["pubkey"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

            val uriBuilder = "content://${CitrineContract.AUTHORITY}/events/by_kind".toUri()
                .buildUpon()
                .appendQueryParameter("kind", kind.toString())
                .appendQueryParameter("limit", limit.toString())
                .appendQueryParameter("offset", offset.toString())

            pubkey?.let { uriBuilder.appendQueryParameter("pubkey", it) }

            val cursor = resolver.query(uriBuilder.build(), null, null, null, null)
            val result = cursorToJson(cursor)

            call.respondText(result, ContentType.Application.Json)
        } catch (e: Exception) {
            Log.e(Citrine.TAG, "Error querying events by kind", e)
            call.respondText(
                """{"error": "${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    suspend fun respondRawQuery(call: ApplicationCall, uriString: String) {
        call.response.headers.appendIfAbsent("Access-Control-Allow-Origin", "*")
        val uri = uriString.toUri()
        val cursor = resolver.query(uri, null, null, null, null)
        call.respondText(cursorToJson(cursor), ContentType.Application.Json)
    }

    private fun cursorToJson(cursor: Cursor?): String {
        if (cursor == null) {
            return "[]"
        }
        val result = mutableListOf<Map<String, Any?>>()
        val columnNames = cursor.columnNames
        while (cursor.moveToNext()) {
            val row = mutableMapOf<String, Any?>()
            for (columnName in columnNames) {
                val index = cursor.getColumnIndex(columnName)
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> row[columnName] = null
                    Cursor.FIELD_TYPE_INTEGER -> row[columnName] = cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> row[columnName] = cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> row[columnName] = cursor.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> row[columnName] = cursor.getBlob(index)?.let { String(it) }
                }
            }
            result.add(row)
        }
        cursor.close()
        return objectMapper.writeValueAsString(result)
    }
}
