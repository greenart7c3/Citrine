package com.greenart7c3.citrine

import kotlin.system.measureNanoTime
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Micro-benchmark comparing the old tag-array scan against a direct column read
 * for NIP-40 expiration checks. Runs on the JVM and stays self-contained so it
 * can execute under `./gradlew test` without Android/Room dependencies.
 */
class ExpirationCheckBenchmark {
    private data class StoredEvent(
        val id: String,
        val expiresAt: Long?,
        val tags: Array<Array<String>>,
    )

    private fun buildEvents(count: Int, expiredRatio: Double = 0.1): List<StoredEvent> {
        val now = 1_700_000_000L
        return List(count) { i ->
            val expiresAt = when {
                i % 3 == 0 && (i.toDouble() / count) < expiredRatio -> now - 10_000
                i % 3 == 0 -> now + 60 * 60 * 24
                else -> null
            }
            val tags = buildList<Array<String>> {
                repeat(8) { add(arrayOf("p", "pubkey-$i-$it")) }
                repeat(4) { add(arrayOf("e", "event-$i-$it", "", "reply")) }
                if (expiresAt != null) add(arrayOf("expiration", expiresAt.toString()))
                repeat(3) { add(arrayOf("t", "topic-$i-$it")) }
            }.toTypedArray()
            StoredEvent(id = "id-$i", expiresAt = expiresAt, tags = tags)
        }
    }

    private fun isExpiredViaTags(event: StoredEvent, now: Long): Boolean {
        val ts = event.tags
            .firstOrNull { it.getOrNull(0) == "expiration" }
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return false
        return ts < now
    }

    private fun isExpiredViaColumn(event: StoredEvent, now: Long): Boolean {
        val ts = event.expiresAt ?: return false
        return ts < now
    }

    @Test
    fun column_access_is_faster_than_tag_parsing() {
        val events = buildEvents(count = 20_000)
        val now = 1_700_000_000L
        val iterations = 20

        // Warm-up so JIT compiles both paths before measurement.
        repeat(3) {
            events.forEach { isExpiredViaTags(it, now) }
            events.forEach { isExpiredViaColumn(it, now) }
        }

        var tagsNanos = 0L
        var columnNanos = 0L
        var sink = 0
        repeat(iterations) {
            tagsNanos += measureNanoTime {
                events.forEach { if (isExpiredViaTags(it, now)) sink++ }
            }
            columnNanos += measureNanoTime {
                events.forEach { if (isExpiredViaColumn(it, now)) sink++ }
            }
        }

        val tagsExpired = events.count { isExpiredViaTags(it, now) }
        val columnExpired = events.count { isExpiredViaColumn(it, now) }
        assertTrue(
            "Both strategies must agree on expired count ($tagsExpired vs $columnExpired)",
            tagsExpired == columnExpired,
        )

        println("Tag-array scan:  ${tagsNanos / iterations} ns/pass over ${events.size} events")
        println("Column access:   ${columnNanos / iterations} ns/pass over ${events.size} events")
        println("Speedup:         ${"%.2f".format(tagsNanos.toDouble() / columnNanos.toDouble())}x")
        assertTrue(
            "Expected column access ($columnNanos ns) to beat tag scan ($tagsNanos ns)",
            columnNanos < tagsNanos,
        )
        // Keeps the sink reachable so the JIT can't eliminate the loops above.
        assertTrue(sink >= 0)
    }
}
