package com.sk.gallery.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sk.gallery.cloud.DriveBackupManager
import com.sk.gallery.data.db.UploadDatabase
import com.sk.gallery.data.db.UploadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

class ExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GalleryBySK"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ExportWorkerChannel"

        const val KEY_PROGRESS = "PROGRESS"
        const val KEY_PROCESSED = "PROCESSED"
        const val KEY_TOTAL = "TOTAL"
        const val KEY_STATUS = "STATUS"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = UploadDatabase.getDatabase(applicationContext)
        val dao = database.uploadDao()

        val pendingUploadsInitial = dao.getPendingOrUploading()
        if (pendingUploadsInitial.isEmpty()) {
            return@withContext Result.success()
        }

        // Reset PAUSED items to PENDING so UI updates properly to 'Running' state
        pendingUploadsInitial.filter { it.status == UploadEntity.STATUS_PAUSED }.forEach {
            dao.update(it.copy(status = UploadEntity.STATUS_PENDING))
        }

        val deviceId = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
        val deviceFolderId = try {
            DriveBackupManager.getOrCreateDeviceFolder(applicationContext, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "ExportWorker: Failed to get/create device folder", e)
            return@withContext Result.retry()
        }

        val totalSize = pendingUploadsInitial.sumOf { it.fileSizeBytes }
        val totalFiles = dao.getTotalCount()

        // Promote to Foreground Service — required on API 31+
        try {
            setForeground(createForegroundInfo(0, totalFiles, "Checking storage quota..."))
        } catch (e: Exception) {
            Log.w(TAG, "ExportWorker: Could not promote to foreground service: ${e.message}")
        }

        var processedCount = 0
        
        var scanner: com.sk.gallery.data.MediaStoreScanner? = null
        val recentlyUploaded = mutableMapOf<String, String>()
        var uploadCount = 0
        
        suspend fun flushManifestUpdates() {
            if (recentlyUploaded.isNotEmpty()) {
                scanner?.updateManifestLocally { latestManifest ->
                    if (latestManifest != null) {
                        val newEntries = latestManifest.entries.toMutableMap()
                        for ((hashId, cloudFileId) in recentlyUploaded) {
                            val existing = newEntries[hashId]
                            if (existing != null) {
                                newEntries[hashId] = existing.copy(cloudFileId = cloudFileId)
                            }
                        }
                        latestManifest.copy(
                            lastUpdatedTimestamp = System.currentTimeMillis(),
                            entries = newEntries
                        )
                    } else null
                }
                recentlyUploaded.clear()
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext)
                    .sendBroadcast(android.content.Intent("com.sk.gallery.SYNC_COMPLETED"))
            }
        }
        
        try {
            setProgress(
            workDataOf(
                KEY_PROGRESS to 0,
                KEY_PROCESSED to 0,
                KEY_TOTAL to totalFiles,
                KEY_STATUS to "Syncing metadata..."
            )
        )

        // Step 1: Upload the manifest FIRST so that the new device knows about everything,
        // and if a file is missing, it will handle it gracefully.
        try {
            safeNotify(processedCount, totalFiles, "Uploading hierarchy_index.json...")
            val manifestFile = File(applicationContext.filesDir, "hierarchy_index.json")
            if (manifestFile.exists()) {
                val existingManifestId = DriveBackupManager.getFileIdByName(applicationContext, "hierarchy_index.json", deviceFolderId)
                if (existingManifestId == null) {
                    DriveBackupManager.uploadPlainFile(
                        context = applicationContext,
                        localFile = manifestFile,
                        driveFileName = "hierarchy_index.json",
                        parentFolderId = deviceFolderId
                    )
                } else {
                    // Update existing manifest by deleting old and uploading new
                    DriveBackupManager.deleteFile(applicationContext, existingManifestId)
                    DriveBackupManager.uploadPlainFile(
                        context = applicationContext,
                        localFile = manifestFile,
                        driveFileName = "hierarchy_index.json",
                        parentFolderId = deviceFolderId
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ExportWorker: Failed to upload manifest file", e)
            return@withContext Result.retry() // Retry if manifest fails, it's critical
        }

        // Step 2: One-time fast-forward sync with Google Drive
        try {
            safeNotify(0, totalFiles, "Syncing with Google Drive...")
            setProgress(workDataOf(KEY_STATUS to "Syncing with cloud..."))
            
            val remoteFileNames = DriveBackupManager.getAllUploadedFileNames(applicationContext, deviceFolderId)
            var fastForwarded = 0
            
            for (upload in pendingUploadsInitial) {
                if (isStopped) break
                val expectedName = "${upload.fileHash}"
                if (remoteFileNames.contains(expectedName)) {
                    dao.updateStatus(upload.id, UploadEntity.STATUS_COMPLETED)
                    fastForwarded++
                }
            }
            if (fastForwarded > 0) {
                Log.d(TAG, "ExportWorker: Fast-forwarded $fastForwarded already uploaded files.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ExportWorker: Fast-forward sync failed. Will fall back to per-file checking.", e)
        }

        // Re-fetch pending after fast-forward and calculate actual processed amount
        val actualPendingUploads = dao.getPendingOrUploading()
        processedCount = dao.getCountByStatus(UploadEntity.STATUS_COMPLETED)

        // Pre-Upload Storage Check
        val actualTotalSize = actualPendingUploads.sumOf { it.fileSizeBytes }
        val spaceLeft = try {
            DriveBackupManager.checkStorageQuota(applicationContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ExportWorker: Failed to check storage quota — user may not be signed in", e)
            return@withContext Result.failure(
                workDataOf(KEY_STATUS to "NOT_SIGNED_IN")
            )
        }

        if (actualTotalSize > spaceLeft) {
            Log.e(TAG, "ExportWorker: Not enough Google Drive storage. Queue: $actualTotalSize bytes, Left: $spaceLeft bytes")
            safeNotify(processedCount, totalFiles, "Backup Paused: Storage quota exceeded.")
            actualPendingUploads.forEach { dao.updateStatus(it.id, UploadEntity.STATUS_PAUSED) }
            return@withContext Result.failure(workDataOf(KEY_STATUS to "QUOTA_EXCEEDED"))
        }

        // Load manifest once for updating cloudFileIds as uploads complete
        scanner = com.sk.gallery.data.MediaStoreScanner(applicationContext)

        // Step 3: Upload all actual pending files
        for (upload in actualPendingUploads) {
                if (isStopped) {
                    Log.w(TAG, "ExportWorker: Worker stopped. Marking remaining as PENDING.")
                    break
                }

                dao.updateStatus(upload.id, UploadEntity.STATUS_UPLOADING)
                safeNotify(processedCount, totalFiles, "Uploading ${upload.fileName}...")

                val file = File(upload.filePath)
                if (!file.exists()) {
                    Log.w(TAG, "ExportWorker: File missing locally: ${upload.filePath}")
                    dao.updateStatus(upload.id, UploadEntity.STATUS_FAILED)
                    processedCount++
                    continue
                }

                try {
                    val driveFileName = "${upload.fileHash}"
                    
                    var uploadComplete = false
                    var driveFileId: String? = null
                    try {
                        // Skip if already exists on Drive (e.g. from a previous partial run)
                        val existingFileId = DriveBackupManager.getFileIdByName(applicationContext, driveFileName, deviceFolderId)
                        
                        if (existingFileId != null) {
                            Log.d(TAG, "ExportWorker: File $driveFileName already exists on Drive, skipping upload.")
                            driveFileId = existingFileId
                        } else {
                            // Real upload process
                            driveFileId = DriveBackupManager.uploadPlainFile(
                                context = applicationContext,
                                localFile = file,
                                driveFileName = driveFileName,
                                parentFolderId = deviceFolderId,
                                onProgress = { bytesUploaded, _ ->
                                    // Optional: Could update notification with byte progress here if needed
                                }
                            )
                        }
                        uploadComplete = true
                    } finally {
                        withContext(NonCancellable) {
                            if (uploadComplete) {
                                dao.updateStatus(upload.id, UploadEntity.STATUS_COMPLETED)
                            }
                        }
                    }

                    processedCount++
                    uploadCount++

                    // Update manifest entry with cloudFileId for cloud badge
                    if (driveFileId != null) {
                        recentlyUploaded[upload.fileHash] = driveFileId
                    }

                    // Every 10 uploads, save manifest and refresh UI so cloud badges appear progressively
                    if (uploadCount % 10 == 0) {
                        flushManifestUpdates()
                    }

                    setProgress(
                        workDataOf(
                            KEY_PROGRESS to ((processedCount.toFloat() / totalFiles) * 100).toInt(),
                            KEY_PROCESSED to processedCount,
                            KEY_TOTAL to totalFiles,
                            KEY_STATUS to "Uploading..."
                        )
                    )

                } catch (e: kotlinx.coroutines.CancellationException) {
                    // IMPORTANT: When WorkManager cancels the worker (e.g. user hits Pause/Cancel),
                    // coroutines throw a CancellationException. We MUST rethrow this to exit the coroutine loop properly!
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "ExportWorker: Upload failed for ${upload.fileName}", e)
                    val isQuotaExceeded = e is com.google.api.client.googleapis.json.GoogleJsonResponseException && 
                        (e.statusCode == 403 || e.details?.errors?.any { it.reason?.contains("quotaExceeded", ignoreCase = true) == true } == true)
                        
                    if (isQuotaExceeded) {
                        Log.e(TAG, "ExportWorker: Quota exceeded during upload. Pausing queue.")
                        safeNotify(processedCount, totalFiles, "Backup Paused: Google Account storage is full.")
                        withContext(NonCancellable) {
                            dao.updateStatus(upload.id, UploadEntity.STATUS_PAUSED)
                            actualPendingUploads.drop(actualPendingUploads.indexOf(upload) + 1).forEach { dao.updateStatus(it.id, UploadEntity.STATUS_PAUSED) }
                            flushManifestUpdates()
                        }
                        return@withContext Result.failure(workDataOf(KEY_STATUS to "QUOTA_EXCEEDED"))
                    } else if (e is java.io.IOException) {
                        Log.e(TAG, "ExportWorker: Network error. Pausing queue.")
                        safeNotify(processedCount, totalFiles, "Backup Paused: Network connection lost.")
                        withContext(NonCancellable) {
                            dao.updateStatus(upload.id, UploadEntity.STATUS_PAUSED)
                            actualPendingUploads.drop(actualPendingUploads.indexOf(upload) + 1).forEach { dao.updateStatus(it.id, UploadEntity.STATUS_PAUSED) }
                            flushManifestUpdates()
                        }
                        return@withContext Result.failure(workDataOf(KEY_STATUS to "NETWORK_ERROR"))
                    } else {
                        withContext(NonCancellable) {
                            dao.updateStatus(upload.id, UploadEntity.STATUS_FAILED)
                        }
                        processedCount++
                    }
                }
            }

            // Final save of manifest with all cloudFileIds
            flushManifestUpdates()
            
            safeNotify(totalFiles, totalFiles, "Backup Complete ✓")
            return@withContext Result.success()
        } finally {
            if (isStopped) {
                Log.w(TAG, "ExportWorker: Worker paused/cancelled. Updating remaining to STATUS_PAUSED in NonCancellable context.")
                withContext(NonCancellable) {
                    val remaining = dao.getPendingOrUploading()
                    remaining.forEach { dao.updateStatus(it.id, UploadEntity.STATUS_PAUSED) }
                    
                    flushManifestUpdates()
                }
            }
        }
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
                "Cloud Backup",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gallery by SK cloud backup progress"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Gallery Backup")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
                .setContentTitle("Gallery Backup")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(getPendingIntent(applicationContext))
                .setProgress(total, progress, false)
                .setOngoing(true)
                .setSilent(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "ExportWorker: Could not update notification: ${e.message}")
        }
    }
}
