package com.greenart7c3.citrine

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.EventEntity
import com.greenart7c3.citrine.database.EventWithTags
import com.greenart7c3.citrine.database.TagEntity
import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.server.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Streaming contract of [EventRepository.emitByIds] / [EventRepository.batchedQuery] against a
 * real (in-memory) database: id order is kept, oversized and vanished events are skipped and do
 * not count toward the client's limit, content is split into bounded chunks, tags are attached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EventRepositoryStreamTest {
    private lateinit var db: AppDatabase
    private var savedMax = 0

    @Before
    fun setUp() {
        savedMax = Settings.maxEventSize
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Settings.maxEventSize = savedMax
    }

    private fun event(id: String, createdAt: Long, content: String = "x") = EventEntity(id = id, pubkey = "p", createdAt = createdAt, kind = 1, content = content, sig = "s")

    private fun insert(vararg events: EventEntity) = runBlocking { db.eventDao().insertEvents(events.toList()) }

    private fun emit(ids: List<String>): Pair<Int, List<List<EventWithTags>>> = runBlocking {
        val batches = mutableListOf<List<EventWithTags>>()
        val n = EventRepository.emitByIds(db, ids) { batches.add(it) }
        n to batches
    }

    @Test
    fun keepsIdOrderAndSkipsMissingAndOversized() {
        Settings.maxEventSize = 5
        insert(event("a", 1), event("b", 2), event("big", 3, "toolong"))

        val (n, batches) = emit(listOf("b", "gone", "big", "a"))

        assertEquals(2, n)
        assertEquals(listOf("b", "a"), batches.flatten().map { it.event.id })
    }

    @Test
    fun splitsContentIntoBoundedChunksAndAttachesTags() {
        val half = "y".repeat(600 * 1024)
        insert(event("a", 1, half), event("b", 2, half), event("c", 3))
        runBlocking {
            db.eventDao().insertTags(
                listOf(TagEntity(pkEvent = "c", position = 0, col0Name = "t", col1Value = "v", col2Differentiator = null, col3Amount = null, col4Plus = emptyList(), kind = 1)),
            )
        }

        val (n, batches) = emit(listOf("a", "b", "c"))

        assertEquals(3, n)
        // 600K + 600K is over the 1M-char chunk budget, so "b" starts a new chunk.
        assertEquals(listOf(listOf("a"), listOf("b", "c")), batches.map { b -> b.map { it.event.id } })
        assertEquals(listOf("t"), batches.flatten().single { it.event.id == "c" }.tags.map { it.col0Name })
    }

    @Test
    fun skippedEventsDoNotEatIntoClientLimit() = runBlocking {
        Settings.maxEventSize = 5
        insert(event("e1", 1), event("e2", 2), event("big", 3, "toolong"))

        val got = mutableListOf<String>()
        val sent = EventRepository.batchedQuery(db, EventFilter(kinds = setOf(1)), clientLimit = 2) { batch ->
            batch.forEach { got.add(it.event.id) }
        }

        assertEquals(2, sent)
        assertEquals(listOf("e2", "e1"), got)
    }
}
