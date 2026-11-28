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
        val bStartsANewChunkSinceTwoHalvesExceedTheCharBudget = listOf(listOf("a"), listOf("b", "c"))
        assertEquals(bStartsANewChunkSinceTwoHalvesExceedTheCharBudget, batches.map { b -> b.map { it.event.id } })
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

    @Test
    fun emitWithTagsRepairsDuplicateTagsAboveChunkBudgetOnTheFly() = runBlocking {
        val dao = db.eventDao()
        val ev = event("dup", 10)
        insert(ev)

        val oneCopy = (0 until 100).map { pos ->
            TagEntity(
                pkEvent = "dup",
                position = pos,
                col0Name = "p",
                col1Value = "pk-$pos",
                col2Differentiator = null,
                col3Amount = null,
                col4Plus = emptyList(),
                kind = 1,
            )
        }
        val copiesAboveTheTenThousandRowChunkTagBudget = 105
        repeat(copiesAboveTheTenThousandRowChunkTagBudget) { dao.insertTags(oneCopy) }
        assertEquals(10_500, dao.countTagsForEvent("dup"))

        val (n, batches) = emit(listOf("dup"))

        assertEquals(1, n)
        assertEquals(100, batches.flatten().single().tags.size)
        assertEquals(100, dao.countTagsForEvent("dup"))
    }

    @Test
    fun emitWithTagsSkipsEventExceedingMaxTagsAndMaxTagChars() = runBlocking {
        val dao = db.eventDao()
        val normal = event("ok", 1)
        val tooManyTags = event("many", 2)
        val hugeTagText = event("bigtext", 3)
        insert(normal, tooManyTags, hugeTagText)

        dao.insertTags(
            listOf(
                TagEntity(pkEvent = "ok", position = 0, col0Name = "t", col1Value = "v", col2Differentiator = "d", col3Amount = "a", col4Plus = listOf("extra1", "extra2"), kind = 1),
                TagEntity(pkEvent = "many", position = 0, col0Name = "p", col1Value = "1", col2Differentiator = null, col3Amount = null, col4Plus = emptyList(), kind = 1),
                TagEntity(pkEvent = "many", position = 1, col0Name = "p", col1Value = "2", col2Differentiator = null, col3Amount = null, col4Plus = emptyList(), kind = 1),
                TagEntity(pkEvent = "many", position = 2, col0Name = "p", col1Value = "3", col2Differentiator = null, col3Amount = null, col4Plus = emptyList(), kind = 1),
                TagEntity(pkEvent = "bigtext", position = 0, col0Name = "t", col1Value = "x".repeat(500), col2Differentiator = null, col3Amount = null, col4Plus = emptyList(), kind = 1),
            ),
        )

        val rows = dao.getEventRowsByIds(listOf("ok", "many", "bigtext"))
        val emitted = mutableListOf<EventWithTags>()
        EventRepository.emitWithTags(db, rows, maxTagsPerEvent = 2, maxTagCharsPerEvent = 100) { batch ->
            emitted.addAll(batch)
        }

        assertEquals(listOf("ok"), emitted.map { it.event.id })
        val tag = emitted.single().tags.single()
        assertEquals(listOf("extra1", "extra2"), tag.col4Plus)
        assertEquals("d", tag.col2Differentiator)
        assertEquals("a", tag.col3Amount)
    }

    @Test
    fun estimateFrameCharsCoversSerializedTagHeavyFrame() {
        val tags = (0 until 500).map { i ->
            TagEntity(
                pkEvent = "a".repeat(64),
                position = i,
                col0Name = "p",
                col1Value = i.toString().padStart(64, '0'),
                col2Differentiator = "wss://relay.example.com",
                col3Amount = "petname",
                col4Plus = listOf("extra"),
                kind = 3,
            )
        }
        val ewt = EventWithTags(
            event = EventEntity(
                id = "a".repeat(64),
                pubkey = "b".repeat(64),
                createdAt = 1_700_000_000L,
                kind = 3,
                content = "",
                sig = "c".repeat(128),
            ),
            tags = tags,
        )
        val escapedSubId = "\"sub-1\""
        val estimate = with(EventRepository) { ewt.estimateFrameChars(escapedSubId) }
        val frame = with(EventRepository) { ewt.toEventFrame(escapedSubId) }

        assertEquals(true, estimate >= frame.length)
        val tightOverAllocationChars = 2_000
        assertEquals(true, estimate - frame.length < tightOverAllocationChars)
    }
}
