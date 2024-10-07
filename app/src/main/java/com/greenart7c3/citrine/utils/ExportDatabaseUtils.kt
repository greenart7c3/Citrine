package com.greenart7c3.citrine.utils

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.CreateMode
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import java.util.Date

object ExportDatabaseUtils {
    fun exportDatabase(
        database: AppDatabase,
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
            val events = database.eventDao().getAllIds()
            events.forEachIndexed { index, it ->
                val event = database.eventDao().getById(it)!!
                val json = event.toEvent().toJson() + "\n"
                writer?.write(json)
                onProgress("Exported ${index + 1}/${events.size}")
            }
        }
    }
}
