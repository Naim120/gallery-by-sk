package com.sk.gallery.worker

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sk.gallery.auth.GoogleSignInManager
import com.sk.gallery.cloud.DriveVaultManager
import com.sk.gallery.data.MediaStoreScanner
import com.sk.gallery.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GalleryBySK"
        const val KEY_PROGRESS = "PROGRESS"
        const val KEY_CURRENT_FILE = "CURRENT_FILE"
        const val KEY_PROCESSED = "PROCESSED"
        const val KEY_TOTAL = "TOTAL"
        const val KEY_UPLOADED = "UPLOADED"
        const val KEY_STATUS = "STATUS"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "SyncWorker: Starting background synchronization...")
        val account = GoogleSignInManager.getLastSignedInAccount(applicationContext)
        if (account == null) {
            Log.e(TAG, "SyncWorker: User is not signed in to Google Account. Aborting.")
            return@withContext Result.failure()
        }

        try {
            val driveService = GoogleSignInManager.getDriveService(applicationContext, account)
            val vaultManager = DriveVaultManager(driveService)
            val scanner = MediaStoreScanner(applicationContext)

            // Step 1: Scan local media store
            Log.d(TAG, "SyncWorker: Scanning local MediaStore...")
            val localIndex = scanner.scanMediaStore()
            val totalFiles = localIndex.entries.size
            Log.d(TAG, "SyncWorker: Local scan found $totalFiles total media items.")

            if (totalFiles == 0) {
                Log.d(TAG, "SyncWorker: No local files to sync. Sync complete.")
                setProgressAsync(workDataOf(
                    KEY_PROGRESS to 100,
                    KEY_STATUS to "No files to sync",
                    KEY_PROCESSED to 0,
                    KEY_TOTAL to 0,
                    KEY_UPLOADED to 0
                ))
                return@withContext Result.success()
            }

            // Step 2: Fetch remote cloud manifest and appDataFolder files in ONE batch call
            Log.d(TAG, "SyncWorker: Batch fetching all remote files from appDataFolder...")
            val appDataFilesMap = try {
                vaultManager.fetchAllAppDataFilesMap()
            } catch (e: Exception) {
                Log.e(TAG, "SyncWorker: Failed to fetch remote file list", e)
                emptyMap()
            }

            val remoteManifestDriveFile = appDataFilesMap[DriveVaultManager.MANIFEST_FILE_NAME]
            val remoteIndex = if (remoteManifestDriveFile != null) {
                vaultManager.downloadManifestById(remoteManifestDriveFile.id)
            } else null

            val remoteEntries = remoteIndex?.entries ?: emptyMap()
            Log.d(TAG, "SyncWorker: Batch fetch completed. Remote has ${appDataFilesMap.size} files.")

            val recentlySynced = mutableMapOf<String, com.sk.gallery.model.FileEntry>()
            var processedCount = 0
            var uploadCount = 0
            
            suspend fun flushManifestUpdates() {
                if (recentlySynced.isNotEmpty()) {
                    scanner.updateManifestLocally { latestManifest ->
                        if (latestManifest != null) {
                            val newEntries = latestManifest.entries.toMutableMap()
                            for ((hashId, entry) in recentlySynced) {
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
                    recentlySynced.clear()
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext)
                        .sendBroadcast(android.content.Intent("com.sk.gallery.SYNC_COMPLETED"))
                }
            }

            // Step 3: Iterate & upload missing or modified binary files
            for ((hashId, entry) in localIndex.entries) {
                if (isStopped) {
                    Log.w(TAG, "SyncWorker: Worker was stopped/cancelled by OS.")
                    break
                }

                processedCount++
                val progress = (processedCount * 100) / totalFiles
                val obfuscatedName = "$hashId.bin"
                val existingRemoteFile = appDataFilesMap[obfuscatedName]
                val remoteEntry = remoteEntries[hashId]

                val needsUpload = existingRemoteFile == null || (remoteEntry != null && entry.sha256Checksum != remoteEntry.sha256Checksum)

                if (needsUpload) {
                    Log.d(TAG, "SyncWorker: [$processedCount/$totalFiles] Processing ${entry.fileName}...")
                    setProgressAsync(workDataOf(
                        KEY_PROGRESS to progress,
                        KEY_CURRENT_FILE to entry.fileName,
                        KEY_PROCESSED to processedCount,
                        KEY_TOTAL to totalFiles,
                        KEY_UPLOADED to uploadCount,
                        KEY_STATUS to "Uploading ${entry.fileName}"
                    ))

                    val tempFile = resolveLocalFile(entry)
                    if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                        try {
                            val uploadedFile = vaultManager.uploadBinaryResumable(
                                localFile = tempFile,
                                hashId = hashId,
                                mimeType = entry.mimeType,
                                existingCloudFileId = existingRemoteFile?.id
                            )
                            recentlySynced[hashId] = entry.copy(cloudFileId = uploadedFile.id)
                            uploadCount++
                            Log.d(TAG, "SyncWorker: Uploaded ${entry.fileName} successfully! Cloud ID: ${uploadedFile.id}")
                            
                            // Every 10 uploads, save manifest and refresh UI so cloud badges appear progressively
                            if (uploadCount % 10 == 0) {
                                flushManifestUpdates()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "SyncWorker: Failed to upload ${entry.fileName}: ${e.localizedMessage}")
                        } finally {
                            if (tempFile.parentFile?.name == "sync_temp") {
                                tempFile.delete()
                            }
                        }
                    } else {
                        Log.w(TAG, "SyncWorker: File unreadable or empty: ${entry.fileName} (${entry.relativePath})")
                    }
                } else {
                    val cloudId = existingRemoteFile?.id ?: remoteEntry?.cloudFileId
                    recentlySynced[hashId] = entry.copy(cloudFileId = cloudId)
                    if (processedCount % 50 == 0 || processedCount == totalFiles) {
                        Log.d(TAG, "SyncWorker: [$processedCount/$totalFiles] Synced: ${entry.fileName}")

                        // Periodically save manifest and update UI so cloud badges appear during long syncs
                        flushManifestUpdates()

                        setProgressAsync(workDataOf(
                            KEY_PROGRESS to progress,
                            KEY_CURRENT_FILE to entry.fileName,
                            KEY_PROCESSED to processedCount,
                            KEY_TOTAL to totalFiles,
                            KEY_UPLOADED to uploadCount,
                            KEY_STATUS to "Synced: $processedCount/$totalFiles"
                        ))
                    }
                }
            }

            // Step 4: Finalize updated manifest index
            Log.d(TAG, "SyncWorker: Finalizing local manifest with $uploadCount new uploads...")
            flushManifestUpdates()
            val manifestFile = scanner.getLocalManifestFile()
            vaultManager.uploadManifest(manifestFile, remoteManifestDriveFile?.id)

            Log.d(TAG, "SyncWorker: Cloud sync completed successfully! Total uploaded: $uploadCount files.")

            // Notify UI to refresh cloud badges
            val intent = android.content.Intent("com.sk.gallery.SYNC_COMPLETED")
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

            setProgressAsync(workDataOf(
                KEY_PROGRESS to 100,
                KEY_STATUS to "Sync Completed! ($uploadCount new files uploaded)",
                KEY_PROCESSED to totalFiles,
                KEY_TOTAL to totalFiles,
                KEY_UPLOADED to uploadCount
            ))

            return@withContext Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker: Error during sync execution", e)
            return@withContext Result.retry()
        }
    }

    private fun resolveLocalFile(entry: FileEntry): File? {
        val externalStorageDir = Environment.getExternalStorageDirectory()

        // 1. Direct file access via root external storage
        val directFile = File(externalStorageDir, entry.relativePath)
        if (directFile.exists() && directFile.canRead()) {
            return directFile
        }

        // 2. Direct absolute path
        val absoluteFile = File(entry.relativePath)
        if (absoluteFile.exists() && absoluteFile.canRead()) {
            return absoluteFile
        }

        // 3. Fallback: Copy via ContentResolver if file is in Scoped Storage (WhatsApp / Android/media)
        val tempDir = File(applicationContext.cacheDir, "sync_temp").apply { mkdirs() }
        val tempFile = File(tempDir, "${entry.hashId}.tmp")

        return try {
            val contentUri = if (entry.mimeType.startsWith("video")) {
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
            val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(entry.fileName)

            val cursor = applicationContext.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                    val uri = Uri.withAppendedPath(contentUri, id.toString())
                    applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            Log.w(TAG, "resolveLocalFile failed for ${entry.fileName}", e)
            null
        }
    }
}
