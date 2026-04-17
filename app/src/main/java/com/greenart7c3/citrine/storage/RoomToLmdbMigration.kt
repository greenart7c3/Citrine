package com.greenart7c3.citrine.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.greenart7c3.citrine.Citrine
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.utils.EventFactory
import java.io.File

/**
 * One-shot migration from the legacy Room database (`citrine_database`) to the
 * LMDB-backed [EventStore]. We read the raw SQLite file directly instead of
 * going through Room so we don't need to keep the whole Room stack compiled in
 * just to run a migration once.
 *
 * Every event row is reassembled with its tags via [EventFactory.create] and
 * emitted through [emit]; on success the caller renames the DB out of the way
 * so the migration doesn't run again.
 */
internal object RoomToLmdbMigration {
    fun run(context: Context, legacyDb: File, emit: (Event) -> Unit): Int {
        if (!legacyDb.exists()) return 0
        val db = SQLiteDatabase.openDatabase(legacyDb.path, null, SQLiteDatabase.OPEN_READONLY)
        var migrated = 0
        try {
            val tagsByEvent = loadTagsByEvent(db)
            db.rawQuery(
                "SELECT id, pubkey, createdAt, kind, content, sig FROM EventEntity",
                null,
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("id")
                val pubkeyIdx = cursor.getColumnIndexOrThrow("pubkey")
                val createdAtIdx = cursor.getColumnIndexOrThrow("createdAt")
                val kindIdx = cursor.getColumnIndexOrThrow("kind")
                val contentIdx = cursor.getColumnIndexOrThrow("content")
                val sigIdx = cursor.getColumnIndexOrThrow("sig")

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx) ?: continue
                    val pubkey = cursor.getString(pubkeyIdx) ?: continue
                    val createdAt = cursor.getLong(createdAtIdx)
                    val kind = cursor.getInt(kindIdx)
                    val content = cursor.getString(contentIdx) ?: ""
                    val sig = cursor.getString(sigIdx) ?: ""
                    val tags = tagsByEvent[id].orEmpty()
                    try {
                        val event = EventFactory.create(
                            id = id,
                            pubKey = pubkey,
                            createdAt = createdAt,
                            kind = kind,
                            content = content,
                            sig = sig,
                            tags = tags,
                        )
                        emit(event)
                        migrated++
                    } catch (e: Throwable) {
                        Log.w(Citrine.TAG, "Skipping malformed event $id during migration", e)
                    }
                }
            }
        } finally {
            db.close()
        }
        return migrated
    }

    private fun loadTagsByEvent(db: SQLiteDatabase): Map<String, Array<Array<String>>> {
        val raw = HashMap<String, MutableList<IndexedTag>>()
        db.rawQuery(
            "SELECT pkEvent, position, col0Name, col1Value, col2Differentiator, col3Amount, col4Plus " +
                "FROM TagEntity",
            null,
        ).use { cursor ->
            val pkEventIdx = cursor.getColumnIndexOrThrow("pkEvent")
            val positionIdx = cursor.getColumnIndexOrThrow("position")
            val col0Idx = cursor.getColumnIndexOrThrow("col0Name")
            val col1Idx = cursor.getColumnIndexOrThrow("col1Value")
            val col2Idx = cursor.getColumnIndexOrThrow("col2Differentiator")
            val col3Idx = cursor.getColumnIndexOrThrow("col3Amount")
            val col4Idx = cursor.getColumnIndexOrThrow("col4Plus")

            while (cursor.moveToNext()) {
                val pkEvent = cursor.getString(pkEventIdx) ?: continue
                val position = cursor.getInt(positionIdx)
                val parts = ArrayList<String>(5)
                cursor.getString(col0Idx)?.let { parts.add(it) }
                cursor.getString(col1Idx)?.let { parts.add(it) }
                cursor.getString(col2Idx)?.let { parts.add(it) }
                cursor.getString(col3Idx)?.let { parts.add(it) }
                parseCol4Plus(cursor.getString(col4Idx))?.let { parts.addAll(it) }
                raw.getOrPut(pkEvent) { ArrayList() }.add(IndexedTag(position, parts.toTypedArray()))
            }
        }
        return raw.mapValues { entry ->
            entry.value.sortedBy { it.position }.map { it.tag }.toTypedArray()
        }
    }

    private fun parseCol4Plus(raw: String?): List<String>? {
        if (raw.isNullOrEmpty()) return null
        // Converters stored this via Jackson: either "[]" or a JSON array of strings.
        return try {
            MAPPER.readValue(raw, Array<String>::class.java).toList()
        } catch (_: Throwable) {
            null
        }
    }

    private val MAPPER by lazy { com.fasterxml.jackson.module.kotlin.jacksonObjectMapper() }

    private data class IndexedTag(val position: Int, val tag: Array<String>)
}
