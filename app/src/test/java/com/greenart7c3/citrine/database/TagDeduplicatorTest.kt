package com.greenart7c3.citrine.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TagDeduplicatorTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun event(id: String, kind: Int, createdAt: Long = 100L) = EventEntity(
        id = id,
        pubkey = "pk",
        createdAt = createdAt,
        kind = kind,
        content = "",
        sig = "sig",
    )

    private fun tag(eventId: String, position: Int, value: String, kind: Int = 3) = TagEntity(
        pkEvent = eventId,
        position = position,
        col0Name = "p",
        col1Value = value,
        col2Differentiator = null,
        col3Amount = null,
        col4Plus = emptyList(),
        kind = kind,
    )

    @Test
    fun deduplicateEventRemovesRepeatedCopiesAcrossChunksAndKeepsEarliestRows() = runBlocking {
        val dao = db.eventDao()
        dao.insertEvents(listOf(event("contact1", kind = 3)))

        val original = (0 until 4).map { i -> tag("contact1", i, "orig-$i") }
        dao.insertTags(original)
        val reIngestionsAfterTheOriginal = 4
        repeat(reIngestionsAfterTheOriginal) { copy ->
            dao.insertTags((0 until 4).map { i -> tag("contact1", i, "dup-$copy-$i") })
        }
        assertEquals(20, dao.countTagsForEvent("contact1"))

        val removed = TagDeduplicator.deduplicateEvent(dao, "contact1", deleteChunkSize = 5)

        assertEquals(16, removed)
        assertEquals(4, dao.countTagsForEvent("contact1"))
        val kept = dao.getById("contact1")!!.tags.sortedBy { it.position }
        assertEquals(listOf("orig-0", "orig-1", "orig-2", "orig-3"), kept.map { it.col1Value })
    }

    @Test
    fun deduplicateEventRemovesADuplicateInsertedBeforeTheLastKeptRow() = runBlocking {
        val dao = db.eventDao()
        dao.insertEvents(listOf(event("e1", kind = 3)))

        dao.insertTags(
            listOf(
                tag("e1", 0, "first-0"),
                tag("e1", 0, "dup-0"),
                tag("e1", 1, "first-1"),
                tag("e1", 1, "dup-1"),
            ),
        )

        val removed = TagDeduplicator.deduplicateEvent(dao, "e1")

        assertEquals(2, removed)
        val kept = dao.getById("e1")!!.tags.sortedBy { it.position }
        assertEquals(listOf("first-0", "first-1"), kept.map { it.col1Value })
    }

    @Test
    fun deduplicateEventIsNoOpForCleanAndTaglessEvents() = runBlocking {
        val dao = db.eventDao()
        dao.insertEvents(listOf(event("clean", kind = 1), event("empty", kind = 1)))
        dao.insertTags((0 until 3).map { i -> tag("clean", i, "v-$i", kind = 1) })

        assertEquals(0, TagDeduplicator.deduplicateEvent(dao, "clean"))
        assertEquals(0, TagDeduplicator.deduplicateEvent(dao, "empty"))
        assertEquals(3, dao.countTagsForEvent("clean"))
    }

    @Test
    fun cleanDaoRepairsPriorityAndOtherKindsAndLeavesCleanEventsUntouched() = runBlocking {
        val dao = db.eventDao()
        dao.insertEvents(
            listOf(
                event("k3", kind = 3, createdAt = 10),
                event("k30000", kind = 30000, createdAt = 20),
                event("clean", kind = 1, createdAt = 30),
            ),
        )
        repeat(3) {
            dao.insertTags(listOf(tag("k3", 0, "a", kind = 3), tag("k3", 1, "b", kind = 3)))
        }
        repeat(2) {
            dao.insertTags(listOf(tag("k30000", 0, "x", kind = 30000)))
        }
        dao.insertTags(listOf(tag("clean", 0, "ok", kind = 1)))

        val result = TagDeduplicator.cleanDao(dao)

        assertEquals(2 to 5, result)
        assertEquals(2, dao.countTagsForEvent("k3"))
        assertEquals(1, dao.countTagsForEvent("k30000"))
        assertEquals(1, dao.countTagsForEvent("clean"))
    }
}
