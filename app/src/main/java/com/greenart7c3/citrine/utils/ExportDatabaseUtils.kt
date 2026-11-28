package com.greenart7c3.citrine.utils

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.CreateMode
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.database.AppDatabase
import com.greenart7c3.citrine.database.toEvent
import com.greenart7c3.citrine.logs.Log
import com.greenart7c3.citrine.server.EventRepository
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val EXPORT_PAGE_SIZE = 500

object ExportDatabaseUtils {
    /**
     * Writes every event to a new JSONL file in [folder]. Events are read in keyset pages over
     * the primary key, with tags attached in bounded chunks by the same helper the REQ path uses;
     * each page is streamed out and dropped before the next one is read, so a large database's
     * tag rows are never held in the heap all at once.
     */
    suspend fun exportDatabase(
        database: AppDatabase,
        context: Context,
        folder: DocumentFile,
        deleteOldFiles: Boolean = false,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dao = database.eventDao()
        val total = dao.countAll()
        if (total > 0) {
            val fileName = "citrine-${Date().time}.jsonl"
            val file = folder.makeFile(
                context,
                fileName,
                mode = CreateMode.CREATE_NEW,
            )
            val op = file?.openOutputStream(context)
            op?.writer()?.use { writer ->
                var exported = 0
                var lastId = ""
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = dao.getEventRowsAfter(lastId, EXPORT_PAGE_SIZE)
                    if (page.isEmpty()) break
                    EventRepository.emitWithTags(database, page) { batch ->
                        for (event in batch) {
                            writer.write(event.toEvent().toJson())
                            writer.write("\n")
                        }
                    }
                    exported += page.size
                    lastId = page.last().id
                    onProgress("Exported $exported/$total")
                }
                if (deleteOldFiles) {
                    Log.d(Citrine.TAG, "Deleting old backups")
                    val files = folder.listFiles().sortedByDescending { it.lastModified() }
                    val filesToDelete = files
                        .filter { it.name != fileName }
                        .drop(5)

                    filesToDelete.forEach { file ->
                        file.delete()
                    }
                }
                delay(1000)
                onProgress("Backup complete")
            }
        }
    }
}
