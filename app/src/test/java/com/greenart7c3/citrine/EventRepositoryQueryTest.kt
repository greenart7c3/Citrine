package com.greenart7c3.citrine

import com.greenart7c3.citrine.server.EventFilter
import com.greenart7c3.citrine.server.EventRepository
import com.greenart7c3.citrine.server.EventRepository.SelectMode
import com.greenart7c3.citrine.server.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the keyset-batched query builder: the (createdAt, id) cursor
 * predicate must land after every filter predicate (param order is the contract —
 * SimpleSQLiteQuery binds positionally) and must compose with the batch LIMIT.
 */
class EventRepositoryQueryTest {
    private var savedMax = 0

    @Before
    fun disableSizeCap() {
        savedMax = Settings.maxEventSize
        Settings.maxEventSize = 0
    }

    @After
    fun restoreSizeCap() {
        Settings.maxEventSize = savedMax
    }

    @Test
    fun sizeCapPredicateIsBoundBeforeCursor() {
        Settings.maxEventSize = 1000
        val (sql, params) = EventRepository.createQuery(
            EventFilter(kinds = setOf(1)),
            SelectMode.FULL_EVENTS,
            cursorCreatedAt = 5L,
            cursorId = "x",
            batchLimit = 10,
        )
        assertTrue(sql.contains("length(EventEntity.content) <= ?"))
        // expiry, kind, size cap, cursor x3, limit
        assertEquals(7, params.size)
        assertEquals(1000, params[2])
        assertEquals(5L, params[3])

        val (countSql, _) = EventRepository.createQuery(EventFilter(), SelectMode.COUNT)
        assertFalse(countSql.contains("length(EventEntity.content)"))
    }

    @Test
    fun cursorPredicateAndBatchLimitAppendInOrder() {
        val (sql, params) = EventRepository.createQuery(
            EventFilter(kinds = setOf(1), since = 100),
            SelectMode.FULL_EVENTS,
            cursorCreatedAt = 500L,
            cursorId = "abc",
            batchLimit = 500,
        )

        val cursorPredicate = "(EventEntity.createdAt < ? OR (EventEntity.createdAt = ? AND EventEntity.id > ?))"
        assertTrue(sql.contains(cursorPredicate))
        // The keyset cursor is only valid under the non-search ordering.
        assertTrue(sql.contains("ORDER BY EventEntity.createdAt DESC, EventEntity.id ASC LIMIT ?"))

        // Positional contract: expiry, since, kind, cursor(createdAt, createdAt, id), limit.
        assertEquals(7, params.size)
        val expiry = params[0] as Long
        assertTrue(expiry > 0)
        assertEquals(100, params[1])
        assertEquals(1, params[2])
        assertEquals(500L, params[3])
        assertEquals(500L, params[4])
        assertEquals("abc", params[5])
        assertEquals(500, params[6])
        assertTrue(expiry >= 100)
    }

    @Test
    fun defaultArgsKeepLegacyLimitBehavior() {
        val (sql, params) = EventRepository.createQuery(
            EventFilter(limit = 7),
            SelectMode.FULL_EVENTS,
        )
        assertTrue(sql.endsWith("LIMIT ?"))
        assertFalse(sql.contains("createdAt < ?"))
        assertEquals(2, params.size)
        val expiry = params[0] as Long
        assertTrue(expiry > 0)
        assertEquals(7, params[1])

        val (noLimitSql, noLimitParams) = EventRepository.createQuery(
            EventFilter(),
            SelectMode.FULL_EVENTS,
        )
        assertFalse(noLimitSql.contains("LIMIT"))
        assertEquals(1, noLimitParams.size)
    }
}
