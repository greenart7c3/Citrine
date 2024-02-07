package com.greenart7c3.citrine

/**
 * Copyright (c) 2023 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import com.vitorpamplona.quartz.events.Event
import java.util.function.Predicate

data class EventFilter(
    val ids: Set<String> = emptySet(),
    val authors: Set<String> = emptySet(),
    val kinds: Set<Int> = emptySet(),
    val tags: Map<String, Set<String>> = emptyMap(),
    val since: Int? = null,
    val until: Int? = null,
    val limit: Int = 10_000,
    private val search: String? = null
) : Predicate<Event> {

    val searchKeywords: Set<String> = search?.let { tokenizeString(search) } ?: emptySet()

    override fun test(event: Event): Boolean {
        if (since != null && event.createdAt < since) {
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
