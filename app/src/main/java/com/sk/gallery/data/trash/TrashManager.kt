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
        val totalItems = entries.size
        val totalBytes = entries.sumOf { it.sizeBytes }
        val showProgress = totalItems > 10 || totalBytes > 50 * 1024 * 1024

        var progressDialog: android.app.AlertDialog? = null
        var progressBar: android.widget.ProgressBar? = null
        var tvProgress: android.widget.TextView? = null

        if (showProgress) {
            try {
                withContext(Dispatchers.Main) {
                    progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                    progressBar!!.max = 100
                    progressBar!!.progress = 0
                    
                    tvProgress = android.widget.TextView(context).apply {
                        text = "Deleting 1 of $totalItems (0%)..."
                        setPadding(16, 16, 16, 16)
                    }
                    
                    val layout = android.widget.LinearLayout(context).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(48, 24, 48, 24)
                        addView(tvProgress)
                        addView(progressBar)
                    }
                    
                    progressDialog = android.app.AlertDialog.Builder(context)
                        .setTitle("Moving to Trash")
                        .setView(layout)
                        .setCancelable(false)
                        .create()
                        
                    progressDialog!!.show()
                }
            } catch (e: Exception) {
                // Fallback if context is not valid for dialog
            }
        }

        withContext(Dispatchers.IO) {
            val trashDir = getTrashDir(context)
            val dao = AppDatabase.getDatabase(context).trashDao()
            val successfulEntries = mutableListOf<FileEntry>()
            
            var processedBytes = 0L
            var lastUpdate = 0L

            for ((index, entry) in entries.withIndex()) {
                val originalFile = File(Environment.getExternalStorageDirectory(), entry.relativePath)
                if (!originalFile.exists()) continue

                val ext = originalFile.extension
                val trashFileName = "${UUID.randomUUID()}.$ext"
                val destFile = File(trashDir, trashFileName)

                try {
                    val originalLastModified = originalFile.lastModified()
                    
                    if (showProgress) {
                        originalFile.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } >= 0) {
                                    output.write(buffer, 0, bytesRead)
                                    processedBytes += bytesRead
                                    
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate > 100) {
                                        lastUpdate = now
                                        val progressPercent = if (totalBytes > 0) ((processedBytes.toDouble() / totalBytes.toDouble()) * 100).toInt() else 0
                                        withContext(Dispatchers.Main) {
                                            progressBar?.progress = progressPercent
                                            tvProgress?.text = "Deleting ${index + 1} of $totalItems ($progressPercent%)..."
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        originalFile.copyTo(destFile, overwrite = true)
                    }
                    
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
                progressDialog?.dismiss()
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
        val totalItems = trashEntries.size
        val totalBytes = trashEntries.sumOf { it.sizeBytes }
        val showProgress = totalItems > 10 || totalBytes > 50 * 1024 * 1024

        var progressDialog: android.app.AlertDialog? = null
        var tvProgress: android.widget.TextView? = null

        if (showProgress) {
            try {
                withContext(Dispatchers.Main) {
                    tvProgress = android.widget.TextView(context).apply {
                        text = "Deleting 1 of $totalItems..."
                        setPadding(48, 48, 48, 48)
                    }
                    
                    progressDialog = android.app.AlertDialog.Builder(context)
                        .setTitle("Deleting Permanently")
                        .setView(tvProgress)
                        .setCancelable(false)
                        .create()
                        
                    progressDialog!!.show()
                }
            } catch (e: Exception) {}
        }

        withContext(Dispatchers.IO) {
            val trashDir = getTrashDir(context)
            val dao = AppDatabase.getDatabase(context).trashDao()

            for ((index, trashEntry) in trashEntries.withIndex()) {
                if (showProgress) {
                    withContext(Dispatchers.Main) {
                        tvProgress?.text = "Deleting ${index + 1} of $totalItems..."
                    }
                }
                
                val trashFile = File(trashDir, trashEntry.trashFileName)
                if (trashFile.exists()) {
                    trashFile.delete()
                }
                dao.deleteTrashEntry(trashEntry.originalHashId)
            }

            withContext(Dispatchers.Main) {
                progressDialog?.dismiss()
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
