package com.greenart7c3.citrine.utils

import android.util.Log
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vitorpamplona.quartz.events.Event

data class NostrSync(
    val data: List<Event>,
) {
    companion object {
        val mapper =
            jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
                .registerModule(
                    SimpleModule()
                        .addDeserializer(NostrSync::class.java, NostrSyncDeserializer()),
                )

        private class NostrSyncDeserializer : StdDeserializer<NostrSync>(NostrSync::class.java) {
            override fun deserialize(
                jp: JsonParser,
                ctxt: DeserializationContext,
            ): NostrSync {
                return fromJson(jp.codec.readTree(jp))
            }
        }

        fun fromJson(jsonObject: JsonNode): NostrSync {
            return NostrSync(
                jsonObject.toList().map {
                    Log.d("NostrSync", it.toString())
                    Event.fromJson(it)
                }.toList(),
            )
        }
    }
}
