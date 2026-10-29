package com.greenart7c3.citrine.json

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * App-wide shared Jackson mapper.
 *
 * Replaces Quartz's `com.vitorpamplona.quartz.nip01Core.jackson.JacksonMapper`
 * (removed in Quartz 2.x). Keeps the same tolerant parsing configuration the
 * Quartz mapper used, so JSON produced by third parties (relay clients,
 * NIP-86 RPC callers, Nsite metadata) continues to deserialize.
 */
object JacksonMapper {
    val mapper =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
}
