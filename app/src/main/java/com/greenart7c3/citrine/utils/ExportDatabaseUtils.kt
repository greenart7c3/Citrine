package com.greenart7c3.citrine.utils

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.CreateMode
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.database.AppDatabase
import java.util.Date
import rust.nostr.sdk.Filter

object ExportDatabaseUtils {
    suspend fun exportDatabase(
        context: Context,
        folder: DocumentFile,
        onProgress: (String) -> Unit,
    ) {
        val file = folder.makeFile(
            context,
            "citrine-${Date().time}.jsonl",
            mode = CreateMode.CREATE_NEW,
        )
        val op = file?.openOutputStream(context)
        op?.writer().use { writer ->
            onProgress("Fetching events from database")
            val events = AppDatabase.getNostrDatabase().query(listOf(Filter()))
            val size = events.len()
            events.toVec().forEachIndexed { index, it ->
                val json = it.asJson() + "\n"
                writer?.write(json)
                onProgress("Exported ${index + 1}/$size")
            }
        }
    }
}
