package com.greenart7c3.citrine.utils

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.CreateMode
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ExportDatabaseUtils {
    suspend fun exportDatabase(
        database: AppDatabase,
        context: Context,
        folder: DocumentFile,
        deleteOldFiles: Boolean = false,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val events = database.eventDao().getAllIds()
        if (events.isNotEmpty()) {
            val fileName = "citrine-${Date().time}.jsonl"
            val file = folder.makeFile(
                context,
                fileName,
                mode = CreateMode.CREATE_NEW,
            )
            val op = file?.openOutputStream(context)
            op?.writer().use { writer ->
                events.forEachIndexed { index, it ->
                    database.eventDao().getById(it)?.let { event ->
                        val json = event.toEvent().toJson() + "\n"
                        writer?.write(json)
                        if (index % 100 == 0) {
                            onProgress("Exported ${index + 1}/${events.size}")
                        }
                    }
                }
                if (deleteOldFiles) {
                    Log.d(Citrine.TAG, "Deleting old backups")
                    folder.listFiles().forEach { file ->
                        if (file.name != fileName) {
                            file.delete()
                        }
                    }
                }
            }
        }
    }
}
