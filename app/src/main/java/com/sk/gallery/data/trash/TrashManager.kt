package com.sk.gallery.data.trash

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.data.local.AppDatabase
import com.sk.gallery.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object TrashManager {
    private const val TAG = "TrashManager"

    private fun getTrashDir(context: Context): File {
        val dir = File(context.filesDir, "Trash")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun moveToTrash(context: Context, entries: List<FileEntry>, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            val trashDir = getTrashDir(context)
            val dao = AppDatabase.getDatabase(context).trashDao()
            val successfulEntries = mutableListOf<FileEntry>()

            for (entry in entries) {
                val originalFile = File(Environment.getExternalStorageDirectory(), entry.relativePath)
                if (!originalFile.exists()) continue

                val ext = originalFile.extension
                val trashFileName = "${UUID.randomUUID()}.$ext"
                val destFile = File(trashDir, trashFileName)

                try {
                    val originalLastModified = originalFile.lastModified()
                    originalFile.copyTo(destFile, overwrite = true)
                    destFile.setLastModified(originalLastModified)
                    
                    // Remove from MediaStore
                    var deleted = false
                    val baseUri = if (entry.mimeType.startsWith("video", ignoreCase = true)) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    try {
                        val rows = context.contentResolver.delete(baseUri, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(originalFile.absolutePath))
                        if (rows > 0) deleted = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting from MediaStore", e)
                    }

                    if (!deleted) {
                        // Fallback delete by ID
                        val projection = arrayOf(MediaStore.MediaColumns._ID)
                        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                        val selectionArgs = arrayOf(entry.fileName)
                        var mediaId: Long? = null
                        try {
                            context.contentResolver.query(baseUri, projection, selection, selectionArgs, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                                    mediaId = cursor.getLong(idIndex)
                                }
                            }
                        } catch (e: Exception) {}

                        val currentMediaId = mediaId
                        if (currentMediaId != null) {
                            val itemUri = ContentUris.withAppendedId(baseUri, currentMediaId)
                            try {
                                context.contentResolver.delete(itemUri, null, null)
                            } catch (e: Exception) {}
                        }
                    }

                    originalFile.delete() // Physical delete from original location

                    val trashEntry = TrashEntry(
                        originalHashId = entry.hashId,
                        originalPath = originalFile.absolutePath,
                        trashFileName = trashFileName,
                        originalFileName = entry.fileName,
                        mimeType = entry.mimeType,
                        sizeBytes = entry.sizeBytes,
                        deletedAt = System.currentTimeMillis()
                    )
                    dao.insertTrashEntry(trashEntry)
                    successfulEntries.add(entry)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to move ${entry.fileName} to trash", e)
                }
            }

            withContext(Dispatchers.Main) {
                MediaRepository.getInstance(context).removeEntriesInstantly(successfulEntries)
                onComplete()
            }
        }
    }

    suspend fun restoreFromTrash(context: Context, trashEntries: List<TrashEntry>, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            val trashDir = getTrashDir(context)
            val dao = AppDatabase.getDatabase(context).trashDao()

            for (trashEntry in trashEntries) {
                val trashFile = File(trashDir, trashEntry.trashFileName)
                if (!trashFile.exists()) {
                    dao.deleteTrashEntry(trashEntry.originalHashId)
                    continue
                }

                val destFile = File(trashEntry.originalPath)
                if (!destFile.parentFile?.exists()!!) {
                    destFile.parentFile?.mkdirs()
                }

                try {
                    val originalLastModified = trashFile.lastModified()
                    val tempFile = File(destFile.absolutePath + ".tmp")
                    trashFile.copyTo(tempFile, overwrite = true)
                    
                    if (tempFile.exists() && tempFile.length() > 0) {
                        if (destFile.exists()) destFile.delete()
                        tempFile.renameTo(destFile)
                        destFile.setLastModified(originalLastModified)
                    }
                    
                    trashFile.delete()
                    dao.deleteTrashEntry(trashEntry.originalHashId)

                    // Trigger scan
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(trashEntry.mimeType)) { _, _ -> }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore ${trashEntry.originalFileName}", e)
                }
            }

            withContext(Dispatchers.Main) {
                MediaRepository.getInstance(context).loadInitialMedia()
                onComplete()
            }
        }
    }

    suspend fun permanentlyDelete(context: Context, trashEntries: List<TrashEntry>, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            val trashDir = getTrashDir(context)
            val dao = AppDatabase.getDatabase(context).trashDao()

            for (trashEntry in trashEntries) {
                val trashFile = File(trashDir, trashEntry.trashFileName)
                if (trashFile.exists()) {
                    trashFile.delete()
                }
                dao.deleteTrashEntry(trashEntry.originalHashId)
            }

            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    suspend fun emptyTrash(context: Context, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            val trashDir = getTrashDir(context)
            val dao = AppDatabase.getDatabase(context).trashDao()
            
            trashDir.listFiles()?.forEach { it.delete() }
            dao.clearAllTrash()

            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    suspend fun cleanupExpiredTrash(context: Context) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).trashDao()
            val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000
            val cutoffTime = System.currentTimeMillis() - thirtyDaysMillis
            
            val expired = dao.getExpiredTrash(cutoffTime)
            if (expired.isNotEmpty()) {
                permanentlyDelete(context, expired) {}
            }
        }
    }
}
