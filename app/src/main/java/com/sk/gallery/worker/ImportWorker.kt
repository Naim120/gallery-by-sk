package com.sk.gallery.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sk.gallery.cloud.DriveBackupManager
import com.sk.gallery.data.db.ImportEntity
import com.sk.gallery.data.db.UploadDatabase
import com.sk.gallery.util.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class ImportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GalleryBySK"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ImportWorkerChannel"

        const val KEY_PROGRESS = "PROGRESS"
        const val KEY_PROCESSED = "PROCESSED"
        const val KEY_TOTAL = "TOTAL"
        const val KEY_STATUS = "STATUS"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = UploadDatabase.getDatabase(applicationContext)
        val dao = database.importDao()

        val pendingImports = dao.getPendingOrDownloading()
        if (pendingImports.isEmpty()) {
            return@withContext Result.success()
        }

        // Reset PAUSED items to PENDING so UI updates properly to 'Running' state
        pendingImports.filter { it.status == ImportEntity.STATUS_PAUSED }.forEach {
            dao.updateStatus(it.id, ImportEntity.STATUS_PENDING)
        }

        val totalFiles = pendingImports.size

        // Promote to Foreground Service
        try {
            setForeground(createForegroundInfo(0, totalFiles, "Starting restore..."))
        } catch (e: Exception) {
            Log.w(TAG, "ImportWorker: Could not promote to foreground service: ${e.message}")
        }

        var processed = 0
        
        var scanner: com.sk.gallery.data.MediaStoreScanner? = null
        val recentlyImported = mutableMapOf<String, com.sk.gallery.model.FileEntry>()
        
        suspend fun flushManifestUpdates() {
            if (recentlyImported.isNotEmpty()) {
                scanner?.updateManifestLocally { latestManifest ->
                    if (latestManifest != null) {
                        val newEntries = latestManifest.entries.toMutableMap()
                        for ((hashId, entry) in recentlyImported) {
                            val existing = newEntries[hashId]
                            if (existing != null) {
                                newEntries[hashId] = existing.copy(cloudFileId = entry.cloudFileId)
                            } else {
                                newEntries[hashId] = entry
                            }
                        }
                        latestManifest.copy(
                            lastUpdatedTimestamp = System.currentTimeMillis(),
                            entries = newEntries
                        )
                    } else null
                }
                recentlyImported.clear()
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext)
                    .sendBroadcast(android.content.Intent("com.sk.gallery.SYNC_COMPLETED"))
            }
        }
        
        try {
            scanner = com.sk.gallery.data.MediaStoreScanner(applicationContext)

            for (import in pendingImports) {
                if (isStopped) {
                    Log.w(TAG, "ImportWorker: Worker stopped. Breaking loop.")
                    break
                }

                dao.updateStatus(import.id, ImportEntity.STATUS_DOWNLOADING)
                safeNotify(processed, totalFiles, "Restoring ${import.fileName}...")

                try {
                    var driveFileName = "${import.fileHash}"
                    
                    val externalStorageDir = Environment.getExternalStorageDirectory()
                    val sourceFile = File(externalStorageDir, import.relativePath)

                    var targetFileName = import.fileName
                    val parentDirStr = File(import.relativePath).parent ?: "Pictures"
                    val relativePathClean = parentDirStr.replace("\\", "/").removeSuffix("/")
                    var destFile = File(externalStorageDir, "$relativePathClean/$targetFileName")

                    val extension = targetFileName.substringAfterLast('.', "").lowercase()
                    val mimeType = if (extension.isNotEmpty()) {
                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
                    } else {
                        if (targetFileName.lowercase().endsWith(".mp4")) "video/mp4" else "image/jpeg"
                    }

                    // Fast-Forward Check: If target file already exists perfectly, skip download
                    if (destFile.exists() && destFile.length() == import.expectedSizeBytes) {
                        Log.d(TAG, "ImportWorker: File already exists locally at ${destFile.absolutePath} with matching size. Skipping download.")
                        withContext(NonCancellable) {
                            dao.updateStatus(import.id, ImportEntity.STATUS_COMPLETED)
                        }
                        processed++
                        setProgress(
                            workDataOf(
                                KEY_PROGRESS to ((processed.toFloat() / totalFiles) * 100).toInt(),
                                KEY_PROCESSED to processed,
                                KEY_TOTAL to totalFiles,
                                KEY_STATUS to "Restoring..."
                            )
                        )
                        continue
                    }

                    // Local optimization: If nested file already exists on device, move it instead of re-downloading
                    if (sourceFile.exists()) {
                            Log.d(TAG, "ImportWorker: File already exists locally at ${sourceFile.absolutePath}. Moving to clean path.")
                            try {
                                if (destFile.exists()) {
                                    val nameWithoutExtension = targetFileName.substringBeforeLast(".")
                                    val fileExtension = targetFileName.substringAfterLast(".", "")
                                    val extensionSuffix = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""
                                    var counter = 1
                                    while (destFile.exists()) {
                                        targetFileName = "$nameWithoutExtension ($counter)$extensionSuffix"
                                        destFile = File(externalStorageDir, "$relativePathClean/$targetFileName")
                                        counter++
                                    }
                                }
                                destFile.parentFile?.mkdirs()
                                val success = sourceFile.renameTo(destFile)
                                if (success) {
                                    destFile.setLastModified(import.dateModified * 1000L)
                                    Log.d(TAG, "ImportWorker: Locally moved file to ${destFile.absolutePath} and set modification timestamp.")
                                    
                                    // Delete old parent dir if now empty
                                    val parentDir = sourceFile.parentFile
                                    if (parentDir != null && parentDir.exists() && parentDir.isDirectory && parentDir.list()?.isEmpty() == true) {
                                        parentDir.delete()
                                    }
                                    
                                    // Notify MediaStore of both the deletion of the old path and the insertion of the new path
                                    android.media.MediaScannerConnection.scanFile(
                                        applicationContext,
                                        arrayOf(sourceFile.absolutePath, destFile.absolutePath),
                                        arrayOf(null, mimeType)
                                    ) { path, uri ->
                                        if (uri != null && path == destFile.absolutePath) {
                                            val values = ContentValues().apply {
                                                put(MediaStore.MediaColumns.DATE_MODIFIED, import.dateModified)
                                                put(MediaStore.MediaColumns.DATE_ADDED, import.dateModified)
                                            }
                                            try {
                                                applicationContext.contentResolver.update(uri, values, null, null)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Failed to update MediaStore timestamps for $path")
                                            }
                                        }
                                    }
                                    withContext(NonCancellable) {
                                        dao.updateStatus(import.id, ImportEntity.STATUS_COMPLETED)
                                    }
                                    
                                    processed++
                                    setProgress(
                                        workDataOf(
                                            KEY_PROGRESS to ((processed.toFloat() / totalFiles) * 100).toInt(),
                                            KEY_PROCESSED to processed,
                                            KEY_TOTAL to totalFiles,
                                            KEY_STATUS to "Restoring..."
                                        )
                                    )
                                    continue
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "ImportWorker: Failed to move local nested file, falling back to download", e)
                            }
                        }

                    var downloadComplete = false
                    try {
                        // Step 1: Pre-check file size in Google Drive before downloading
                        val driveFileMeta = DriveBackupManager.getFileMetadataByName(applicationContext, driveFileName, import.deviceFolderId)

                        if (driveFileMeta == null) {
                            Log.w(TAG, "ImportWorker: File $driveFileName missing from Google Drive. Skipping.")
                            withContext(NonCancellable) { dao.updateStatus(import.id, ImportEntity.STATUS_FAILED) }
                            continue
                        }

                        val remoteSize = driveFileMeta.getSize() ?: 0L
                        val expectedSize = import.expectedSizeBytes
                        
                        if (remoteSize < expectedSize) {
                            Log.e(TAG, "ImportWorker: Remote file size ($remoteSize) is smaller than expected ($expectedSize). File is corrupted or partially uploaded. Skipping.")
                            withContext(NonCancellable) { dao.updateStatus(import.id, ImportEntity.STATUS_FAILED) }
                            continue
                        }

                        val driveFileId = driveFileMeta.id
                        
                        // Step 2: Download file to temporary cache
                        val tempPlainFile = File(applicationContext.cacheDir, "import_${import.fileHash}.tmp")
                        DriveBackupManager.downloadFile(applicationContext, driveFileId, tempPlainFile)

                        // Step 3: Write downloaded file directly to filesystem
                        try {
                            if (destFile.exists()) {
                                val nameWithoutExtension = targetFileName.substringBeforeLast(".")
                                val fileExtension = targetFileName.substringAfterLast(".", "")
                                val extensionSuffix = if (fileExtension.isNotEmpty()) ".$fileExtension" else ""
                                var counter = 1
                                while (destFile.exists()) {
                                    targetFileName = "$nameWithoutExtension ($counter)$extensionSuffix"
                                    destFile = File(externalStorageDir, "$relativePathClean/$targetFileName")
                                    counter++
                                }
                            }
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { out ->
                                FileInputStream(tempPlainFile).use { input ->
                                    input.copyTo(out)
                                }
                            }
                            
                            destFile.setLastModified(import.dateModified * 1000L)
                            Log.d(TAG, "ImportWorker: Wrote imported file directly to ${destFile.absolutePath}")
                            
                            // Trigger MediaStore scan
                            android.media.MediaScannerConnection.scanFile(
                                applicationContext,
                                arrayOf(destFile.absolutePath),
                                arrayOf(mimeType)
                            ) { path, uri ->
                                if (uri != null) {
                                    val values = ContentValues().apply {
                                        put(MediaStore.MediaColumns.DATE_MODIFIED, import.dateModified)
                                        put(MediaStore.MediaColumns.DATE_ADDED, import.dateModified)
                                    }
                                    try {
                                        applicationContext.contentResolver.update(uri, values, null, null)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to update MediaStore timestamps for $path")
                                    }
                                }
                            }
                            
                            val relativePath = destFile.absolutePath.substring(externalStorageDir.absolutePath.length).removePrefix("/")
                            val newHashId = com.sk.gallery.util.FileUtils.hashStringSha256(relativePath)
                            val newEntry = com.sk.gallery.model.FileEntry(
                                hashId = newHashId,
                                relativePath = relativePath,
                                fileName = targetFileName,
                                mimeType = mimeType,
                                sizeBytes = destFile.length(),
                                sha256Checksum = import.fileHash,
                                dateModified = import.dateModified,
                                cloudFileId = driveFileId
                            )
                            recentlyImported[newHashId] = newEntry
                            
                            if (processed % 10 == 0) {
                                flushManifestUpdates()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "ImportWorker: Failed to write file directly to $destFile", e)
                            tempPlainFile.delete()
                            withContext(NonCancellable) { dao.updateStatus(import.id, ImportEntity.STATUS_FAILED) }
                            continue
                        }

                        downloadComplete = true
                    } finally {
                        // Cleanup temps regardless of success or failure
                        val tempEncryptedFile = File(applicationContext.cacheDir, "import_${import.fileHash}.enc")
                        val tempPlainFile = File(applicationContext.cacheDir, "import_${import.fileHash}_plain")
                        if (tempEncryptedFile.exists()) tempEncryptedFile.delete()
                        if (tempPlainFile.exists()) tempPlainFile.delete()

                        withContext(NonCancellable) {
                            if (downloadComplete) {
                                dao.updateStatus(import.id, ImportEntity.STATUS_COMPLETED)
                            } else {
                                // If aborted during Step 4, a partial destFile might exist. Delete it to prevent duplicates like 'file (1).jpg' on resume.
                                if (destFile.exists() && destFile.length() < import.expectedSizeBytes) {
                                    destFile.delete()
                                }
                            }
                        }
                    }

                    processed++
                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to ((processed.toFloat() / totalFiles) * 100).toInt(),
                            KEY_PROCESSED to processed,
                            KEY_TOTAL to totalFiles,
                            KEY_STATUS to "Restoring..."
                        )
                    )

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "ImportWorker: Download failed for ${import.fileName}", e)
                    if (e is java.io.IOException) {
                        Log.e(TAG, "ImportWorker: Network error. Pausing queue.")
                        safeNotify(processed, totalFiles, "Restore Paused: Network connection lost.")
                        withContext(NonCancellable) {
                            dao.updateStatus(import.id, ImportEntity.STATUS_PAUSED)
                            val remaining = dao.getPendingOrDownloading()
                            remaining.forEach { dao.updateStatus(it.id, ImportEntity.STATUS_PAUSED) }
                            
                            flushManifestUpdates()
                        }
                        return@withContext Result.failure(workDataOf(KEY_STATUS to "NETWORK_ERROR"))
                    } else {
                        dao.updateStatus(import.id, ImportEntity.STATUS_FAILED)
                        processed++
                    }
                }
            }
        } finally {
            if (isStopped) {
                Log.w(TAG, "ImportWorker: Worker paused/cancelled. Updating remaining to STATUS_PAUSED in NonCancellable context.")
                withContext(NonCancellable) {
                    val remaining = dao.getPendingOrDownloading()
                    remaining.forEach { dao.updateStatus(it.id, ImportEntity.STATUS_PAUSED) }
                    
                    flushManifestUpdates()
                }
            }
        }

        flushManifestUpdates()

        safeNotify(totalFiles, totalFiles, "Restore Complete ✓")
        return@withContext Result.success()
    }

    private fun getPendingIntent(context: Context): android.app.PendingIntent {
        val intent = android.content.Intent(context, com.sk.gallery.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return android.app.PendingIntent.getActivity(
            context,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createForegroundInfo(progress: Int, total: Int, status: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cloud Restore",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gallery by SK cloud restore progress"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Gallery Restore")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(getPendingIntent(applicationContext))
            .setProgress(total, progress, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun safeNotify(progress: Int, total: Int, status: String) {
        try {
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("Gallery Restore")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(getPendingIntent(applicationContext))
                .setProgress(total, progress, false)
                .setOngoing(true)
                .setSilent(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "ImportWorker: Could not update notification: ${e.message}")
        }
    }


}

