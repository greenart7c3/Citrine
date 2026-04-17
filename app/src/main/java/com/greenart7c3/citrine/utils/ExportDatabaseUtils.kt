package com.greenart7c3.citrine.utils

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.file.CreateMode
import com.anggrayudi.storage.file.makeFile
import com.anggrayudi.storage.file.openOutputStream
import com.greenart7c3.citrine.Citrine
import com.greenart7c3.citrine.storage.EventStore
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ExportDatabaseUtils {
    suspend fun exportDatabase(
        store: EventStore,
        context: Context,
        folder: DocumentFile,
        deleteOldFiles: Boolean = false,
        onProgress: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val eventIds = store.getAllIds()
        if (eventIds.isNotEmpty()) {
            val fileName = "citrine-${Date().time}.jsonl"
            val file = folder.makeFile(
                context,
                fileName,
                mode = CreateMode.CREATE_NEW,
            )
            val op = file?.openOutputStream(context)
            op?.writer()?.use { writer ->
                val chunkSize = 1000
                eventIds.chunked(chunkSize).forEachIndexed { index, chunk ->
                    store.getByIds(chunk).forEach { event ->
                        val json = event.toJson() + "\n"
                        writer.write(json)
                        if ((index + 1) * chunkSize > eventIds.size) {
                            onProgress("Exported ${eventIds.size}/${eventIds.size}")
                        } else {
                            onProgress("Exported ${(index + 1) * chunkSize}/${eventIds.size}")
                        }
                    }
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
