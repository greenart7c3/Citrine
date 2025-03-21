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
    val searchKeywords: Set<String> = search?.let { tokenizeString(search) } ?: emptySet()

    override fun test(event: Event): Boolean {
        if (since != null && event.createdAt < since!!) {
            return false
        }

        if (until != null && event.createdAt > until) {
            return false
        }

        if (ids.isNotEmpty() && ids.none { event.id.startsWith(it) }) {
            return false
        }

        if (authors.isNotEmpty() && authors.none { event.pubKey.startsWith(it) }) {
            return false
        }

        if (kinds.isNotEmpty() && event.kind !in kinds) {
            return false
        }

        if (tags.isNotEmpty() && tags.none { testTag(it, event) }) {
            return false
        }

        if (!search.isNullOrBlank() && !testSearch(search, event)) {
            return false
        }

        return true
    }

    private fun testTag(tag: Map.Entry<String, Set<String>>, event: Event): Boolean {
        val eventTags: Set<String> = event.tags.asSequence()
            .filter { it.size > 1 && it[0] == tag.key }
            .map { it[1] }
            .toSet()

        return tag.value.any { it in eventTags }
    }

    private fun testSearch(search: String, event: Event): Boolean {
        val tokens = tokenizeString(search)
        val eventTokens = tokenizeString(event.content)

        return tokens.all { it in eventTokens }
    }

    private fun tokenizeString(string: String): Set<String> {
        return string.split(TOKENIZE_REGEX)
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .toSet()
    }

    companion object {
        val TOKENIZE_REGEX = "[^a-zA-Z0-9]".toRegex()
    }
}
