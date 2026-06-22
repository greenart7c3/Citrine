package com.greenart7c3.citrine.logs

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["timestamp"], name = "idx_log_timestamp"),
    ],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // Epoch milliseconds when the log entry was created.
    val timestamp: Long,
    // Single-letter level: V, D, I, W or E.
    val level: String,
    val tag: String,
    val message: String,
)

private val logDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

fun LogEntity.format(): String = "${logDateFormat.format(Date(timestamp))} $level/$tag: $message"
