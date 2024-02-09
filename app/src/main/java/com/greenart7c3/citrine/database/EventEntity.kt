package com.greenart7c3.citrine.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.EventFactory

@Entity(
    indices = [
        Index(
            value = ["id"],
            name = "id_is_hash",
            unique = true
        ),
        Index(
            value = ["pubkey", "kind"],
            name = "most_common_search_is_pubkey_kind",
            orders = [Index.Order.ASC, Index.Order.ASC]
        )
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val content: String,
    val sig: String
)

data class EventWithTags(
    @Embedded val event: EventEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "pkEvent"
    )
    val tags: List<TagEntity>
)

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            childColumns = ["pkEvent"],
            parentColumns = ["id"],
            onDelete = CASCADE
        )
    ],
    indices = [
        Index(
            value = ["pkEvent"],
            name = "tags_by_pk_event"
        ),
        Index(
            value = ["col0Name", "col1Value"],
            name = "tags_by_tags_on_person_or_events"
        )
    ]
)

data class TagEntity(
    @PrimaryKey(autoGenerate = true) val pk: Long? = null,
    var pkEvent: String? = null,
    val position: Int,

    // Holds 6 columns but can be extended.
    val col0Name: String?,
    val col1Value: String?,
    val col2Differentiator: String?,
    val col3Amount: String?,
    val col4Plus: List<String>
)

class Converters {
    val mapper = jacksonObjectMapper()

    @TypeConverter
    fun fromString(value: String?): List<String> {
        if (value == null) return emptyList()
        if (value == "") return emptyList()
        return mapper.readValue(value)
    }

    @TypeConverter
    fun fromList(list: List<String?>?): String {
        if (list == null) return ""
        if (list.isEmpty()) return ""
        return mapper.writeValueAsString(list)
    }
}

fun EventWithTags.toEvent(): Event {
    return EventFactory.create(
        id = event.id,
        pubKey = event.pubkey,
        createdAt = event.createdAt,
        kind = event.kind,
        content = event.content,
        sig = event.sig,
        tags = tags.map {
            it.toTags()
        }.toTypedArray()
    )
}

fun TagEntity.toTags(): Array<String> {
    return listOfNotNull(
        col0Name,
        col1Value,
        col2Differentiator,
        col3Amount
    ).plus(col4Plus).toTypedArray()
}

fun Event.toEventWithTags(): EventWithTags {
    val dbEvent = EventEntity(
        id = id,
        pubkey = pubKey,
        createdAt = createdAt,
        kind = kind,
        content = content,
        sig = sig
    )

    val dbTags = tags.mapIndexed { index, tag ->
        TagEntity(
            position = index,
            col0Name = tag.getOrNull(0), // tag name
            col1Value = tag.getOrNull(1), // tag value
            col2Differentiator = tag.getOrNull(2), // marker
            col3Amount = tag.getOrNull(3), // value
            col4Plus = if (tag.size > 4) tag.asList().subList(4, tag.size) else emptyList()
        )
    }

    return EventWithTags(dbEvent, dbTags)
}