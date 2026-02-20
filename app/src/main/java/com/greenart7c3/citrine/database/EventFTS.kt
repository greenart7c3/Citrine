package com.greenart7c3.citrine.database

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "event_fts")
@Fts4(contentEntity = EventEntity::class)
data class EventFTS(
    val content: String,
)
