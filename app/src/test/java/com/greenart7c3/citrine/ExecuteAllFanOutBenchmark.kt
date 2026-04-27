package com.greenart7c3.citrine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.system.measureNanoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Micro-benchmark comparing the old per-subscription envelope serialization against
 * the new pre-serialize-once strategy used by `EventSubscription.executeAll`. Runs
 * on the JVM and stays self-contained so it can execute under `./gradlew test`
 * without Android/Room dependencies.
 */
class ExecuteAllFanOutBenchmark {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val nodeFactory: JsonNodeFactory = mapper.nodeFactory

    private fun buildEventJson(): JsonNode = nodeFactory.objectNode().apply {
        put("id", "a".repeat(64))
        put("pubkey", "b".repeat(64))
        put("created_at", 1_700_000_000L)
        put("kind", 1)
        replace(
            "tags",
            nodeFactory.arrayNode().apply {
                repeat(8) { i ->
                    add(
                        nodeFactory.arrayNode().apply {
                            add("p")
                            add("pubkey-$i".padEnd(64, '0'))
                        },
                    )
                }
                repeat(8) { i ->
                    add(
                        nodeFactory.arrayNode().apply {
                            add("e")
                            add("event-$i".padEnd(64, '0'))
                            add("")
                            add("reply")
                        },
                    )
                }
                repeat(10) { i ->
                    add(
                        nodeFactory.arrayNode().apply {
                            add("t")
                            add("topic-$i")
                        },
                    )
                }
                repeat(4) { i ->
                    add(
                        nodeFactory.arrayNode().apply {
                            add("a")
                            add("30023:author$i:slug-$i")
                        },
                    )
                }
            },
        )
        put("content", "lorem ipsum dolor sit amet ".repeat(20))
        put("sig", "c".repeat(128))
    }

    private fun buildSubscriptionIds(count: Int): List<String> = List(count) { i ->
        when (i % 5) {
            0 -> "sub-$i"
            1 -> "sub\"with\"quotes-$i"
            2 -> "sub\\with\\backslash-$i"
            3 -> "subwith-control-$i"
            else -> "long-subscription-id-${"x".repeat(40)}-$i"
        }
    }

    private fun oldPath(eventJson: JsonNode, subscriptionId: String): String = mapper.writeValueAsString(listOf("EVENT", subscriptionId, eventJson))

    private fun newPath(eventJsonStr: String, escapedId: String): String = "[\"EVENT\",$escapedId,$eventJsonStr]"

    @Test
    fun new_path_matches_old_path_semantically() {
        val eventJson = buildEventJson()
        val eventJsonStr = mapper.writeValueAsString(eventJson)
        val ids = listOf("simple", "with\"quote", "with\\slash", "withctrl", "tab\there")

        for (id in ids) {
            val old = oldPath(eventJson, id)
            val escapedId = mapper.writeValueAsString(id)
            val new = newPath(eventJsonStr, escapedId)
            assertEquals(
                "Parsed envelopes must be equal for subscription id '$id'",
                mapper.readTree(old),
                mapper.readTree(new),
            )
        }
    }

    @Test
    fun new_path_is_faster_than_old_path() {
        val eventJson = buildEventJson()
        val subscriptionIds = buildSubscriptionIds(200)
        val iterations = 20

        // Warm-up so the JIT compiles both paths before measurement.
        repeat(3) {
            subscriptionIds.forEach { oldPath(eventJson, it) }
            val warmJsonStr = mapper.writeValueAsString(eventJson)
            val warmEscaped = subscriptionIds.map { mapper.writeValueAsString(it) }
            warmEscaped.forEach { newPath(warmJsonStr, it) }
        }

        var oldNanos = 0L
        var newNanos = 0L
        var sink = 0
        repeat(iterations) {
            oldNanos += measureNanoTime {
                subscriptionIds.forEach { sink += oldPath(eventJson, it).length }
            }
            newNanos += measureNanoTime {
                val eventJsonStr = mapper.writeValueAsString(eventJson)
                subscriptionIds.forEach {
                    val escapedId = mapper.writeValueAsString(it)
                    sink += newPath(eventJsonStr, escapedId).length
                }
            }
        }

        println("Old per-sub envelope serialization: ${oldNanos / iterations} ns/pass over ${subscriptionIds.size} subs")
        println("New pre-serialized envelope:        ${newNanos / iterations} ns/pass over ${subscriptionIds.size} subs")
        println("Speedup:                            ${"%.2f".format(oldNanos.toDouble() / newNanos.toDouble())}x")

        assertTrue(
            "Expected new path ($newNanos ns) to beat old path ($oldNanos ns)",
            newNanos < oldNanos,
        )
        assertTrue(sink >= 0)
    }

    @Test
    fun new_path_with_cached_escaped_ids_is_faster_than_old_path() {
        val eventJson = buildEventJson()
        val subscriptionIds = buildSubscriptionIds(200)
        val escapedIds = subscriptionIds.map { mapper.writeValueAsString(it) }
        val iterations = 20

        repeat(3) {
            subscriptionIds.forEach { oldPath(eventJson, it) }
            val warmJsonStr = mapper.writeValueAsString(eventJson)
            escapedIds.forEach { newPath(warmJsonStr, it) }
        }

        var oldNanos = 0L
        var newNanos = 0L
        var sink = 0
        repeat(iterations) {
            oldNanos += measureNanoTime {
                subscriptionIds.forEach { sink += oldPath(eventJson, it).length }
            }
            newNanos += measureNanoTime {
                val eventJsonStr = mapper.writeValueAsString(eventJson)
                escapedIds.forEach { sink += newPath(eventJsonStr, it).length }
            }
        }

        println("Old per-sub envelope serialization:    ${oldNanos / iterations} ns/pass over ${subscriptionIds.size} subs")
        println("New (cached escaped ids) envelope:     ${newNanos / iterations} ns/pass over ${subscriptionIds.size} subs")
        println("Speedup with cached ids:               ${"%.2f".format(oldNanos.toDouble() / newNanos.toDouble())}x")

        assertTrue(
            "Expected cached-id new path ($newNanos ns) to beat old path ($oldNanos ns)",
            newNanos < oldNanos,
        )
        assertTrue(sink >= 0)
    }
}
