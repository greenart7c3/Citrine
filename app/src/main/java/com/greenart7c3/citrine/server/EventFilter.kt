package com.greenart7c3.citrine.server

import com.vitorpamplona.quartz.nip01Core.core.Event
import java.util.function.Predicate

data class EventFilter(
    val ids: Set<String> = emptySet(),
    val authors: Set<String> = emptySet(),
    val kinds: Set<Int> = emptySet(),
    val tags: Map<String, Set<String>> = emptyMap(),
    var since: Int? = null,
    val until: Int? = null,
    val limit: Int? = null,
    val search: String? = null,
) : Predicate<Event> {
    val searchKeywords: Set<String> = search?.let { tokenizeSearchString(it) } ?: emptySet()

    override fun test(event: Event): Boolean {
        // Cheapest checks first: this runs against every active subscription
        // for every event inserted into the database.
        if (kinds.isNotEmpty() && event.kind !in kinds) {
            return false
        }

        if (since != null && event.createdAt < since!!) {
            return false
        }

        if (until != null && event.createdAt > until) {
            return false
        }

        // O(1) exact match first; fall back to the O(n) prefix scan only on miss.
        if (ids.isNotEmpty() && event.id !in ids && ids.none { event.id.startsWith(it) }) {
            return false
        }

        if (authors.isNotEmpty() && event.pubKey !in authors && authors.none { event.pubKey.startsWith(it) }) {
            return false
        }

        if (tags.isNotEmpty() && !tags.all { testTag(it, event) }) {
            return false
        }

        if (searchKeywords.isNotEmpty() && !testSearch(event)) {
            return false
        }

        return true
    }

    private fun testTag(tag: Map.Entry<String, Set<String>>, event: Event): Boolean = event.tags.any { it.size > 1 && it[0] == tag.key && it[1] in tag.value }

    private fun testSearch(event: Event): Boolean {
        val eventTokens = tokenizeString(event.content)
        return searchKeywords.all { it in eventTokens }
    }

    private fun tokenizeSearchString(string: String): Set<String> = string.split(Regex("\\s+"))
        .filter { it.isNotEmpty() && !it.contains(":") }
        .map { it.lowercase().replace(TOKENIZE_REGEX, "") }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun tokenizeString(string: String): Set<String> = string.split(TOKENIZE_REGEX)
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }
        .toSet()

    companion object {
        val TOKENIZE_REGEX = "[^a-zA-Z0-9]".toRegex()
    }
}
