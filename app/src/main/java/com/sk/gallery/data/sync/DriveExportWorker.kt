package com.sk.gallery.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DriveExportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = com.sk.gallery.data.local.AppPreferences(applicationContext)
        var total = 0
        var currentIndex = 0
        try {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(applicationContext)
                ?: return@withContext Result.failure()

            val driveManager = GoogleDriveManager(applicationContext, account)
            val passphrase = prefs.getCloudPassphrase() ?: return@withContext Result.failure()

            val vaultEntries = com.sk.gallery.data.PrivateVaultManager.getVaultEntries(applicationContext)
            total = vaultEntries.size
            if (vaultEntries.isEmpty()) {
                setProgress(androidx.work.workDataOf("progress" to 100, "status" to "Export Complete"))
                return@withContext Result.success()
            }

            android.util.Log.d("GalleryBySK", "DriveExportWorker: Starting Private Safe export. Total entries: $total")

            val deviceId = android.provider.Settings.Secure.getString(
                applicationContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            val privateSafeFolderId = com.sk.gallery.cloud.DriveBackupManager.getOrCreatePrivateSafeFolder(applicationContext, deviceId)

            // Get already uploaded files in the private safe subdirectory to avoid duplicates
            val existingFiles = driveManager.listEncryptedFiles(privateSafeFolderId)
            val existingNames = existingFiles.map { it.name }.toSet()
            val uploadedNames = existingNames.toMutableSet()
            com.sk.gallery.data.PrivateVaultManager.setCloudStatus(applicationContext, uploadedNames)
            
            val filesToUpload = vaultEntries.filter { !existingNames.contains("${it.hashId}.enc") }
            val alreadyUploadedCount = vaultEntries.size - filesToUpload.size

            if (alreadyUploadedCount > 0 && filesToUpload.isNotEmpty()) {
                setProgress(androidx.work.workDataOf("progress" to 0, "status" to "$alreadyUploadedCount files already exist. Uploading rest..."))
                kotlinx.coroutines.delay(1500)
            } else if (alreadyUploadedCount > 0 && filesToUpload.isEmpty()) {
                setProgress(androidx.work.workDataOf("progress" to 0, "status" to "All files already uploaded!"))
            }

            // Fix progress calculation offset when resuming
            currentIndex = alreadyUploadedCount

            // Note: Sync the vault_map.json FIRST so original filenames are retained even if paused!
            val mapFile = java.io.File(applicationContext.getDir("PrivateVault", Context.MODE_PRIVATE), "vault_map.json")
            if (mapFile.exists()) {
                val cloudMapName = "vault_map.json.enc"
                val tempEncMap = java.io.File(applicationContext.cacheDir, cloudMapName)
                try {
                    com.sk.gallery.data.crypto.CryptoManager.encryptForCloud(mapFile, tempEncMap, passphrase)
                    
                    // Delete old map(s) first, then upload new one
                    existingFiles.filter { it.name == cloudMapName }.forEach { oldFile ->
                        try { driveManager.deleteFile(oldFile.id) } catch (e: Exception) {}
                    }
                    driveManager.uploadFile(tempEncMap, "application/octet-stream", cloudMapName, privateSafeFolderId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "DriveExportWorker: Error exporting vault_map", e)
                } finally {
                    if (tempEncMap.exists()) tempEncMap.delete()
                }
            }
            
            if (filesToUpload.isEmpty()) {
                setProgress(androidx.work.workDataOf("progress" to 100, "status" to "Export Complete"))
                prefs.setPrivateSafeExportPaused(false)
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
                val dateStr = sdf.format(java.util.Date())
                prefs.setPrivateSafeLastExport("Last Exported on $dateStr - $total of $total files")
                return@withContext Result.success()
            }

            var uploadedThisRun = 0

            for ((index, entry) in filesToUpload.withIndex()) {
                currentIndex = index + alreadyUploadedCount
                if (isStopped) {
                    break
                }

                val progress = (currentIndex * 100) / total
                setProgress(androidx.work.workDataOf("progress" to progress, "status" to "Syncing ${currentIndex + 1} of $total"))
                android.util.Log.d("GalleryBySK", "DriveExportWorker: Progress: $progress% (${currentIndex + 1} of $total) - Syncing: ${entry.hashId}")

                val cloudFileName = "${entry.hashId}.enc"

                val originalFile = java.io.File(entry.relativePath)
                val tempPlainFile = java.io.File(applicationContext.cacheDir, "temp_plain_upload_${entry.hashId}")
                val tempEncFile = java.io.File(applicationContext.cacheDir, "temp_upload_${entry.hashId}.enc")
                
                try {
                    // 1. Decrypt the local hardware-encrypted file to get the raw Plaintext
                    com.sk.gallery.data.crypto.CryptoManager.decryptFileLocal(originalFile, tempPlainFile)
                    
                    // 2. Encrypt the raw Plaintext with AES-256-GCM using the user's Passphrase
                    com.sk.gallery.data.crypto.CryptoManager.encryptForCloud(tempPlainFile, tempEncFile, passphrase)
                    
                    // 3. Upload to Google Drive private safe subdirectory
                    driveManager.uploadFile(tempEncFile, "application/octet-stream", cloudFileName, privateSafeFolderId)
                    
                    uploadedNames.add(cloudFileName)
                    com.sk.gallery.data.PrivateVaultManager.setCloudStatus(applicationContext, uploadedNames)
                    uploadedThisRun++
                    
                    // Periodic live UI updates
                    if (uploadedThisRun % 5 == 0) {
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(android.content.Intent("com.sk.gallery.VAULT_SYNC_COMPLETED"))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "DriveExportWorker: Error exporting ${entry.hashId}", e)
                    val isQuotaExceeded = e is com.google.api.client.googleapis.json.GoogleJsonResponseException && 
                        (e.statusCode == 403 || e.details?.errors?.any { it.reason?.contains("quotaExceeded", ignoreCase = true) == true } == true)
                    val isNetworkError = e is java.io.IOException
                    if (isQuotaExceeded) {
                        android.util.Log.e("GalleryBySK", "DriveExportWorker: Quota exceeded during private safe upload.")
                        withContext(NonCancellable) {
                            val progress = (currentIndex * 100) / if (total > 0) total else 1
                            prefs.setPrivateSafeExportProgress(progress)
                            prefs.setPrivateSafeExportStatus("Paused (Storage Full)")
                            prefs.setPrivateSafeExportPaused(true)
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(android.content.Intent("com.sk.gallery.VAULT_SYNC_COMPLETED"))
                        }
                        return@withContext Result.failure()
                    }
                    if (isNetworkError) {
                        android.util.Log.e("GalleryBySK", "DriveExportWorker: Network error. Pausing upload.")
                        withContext(NonCancellable) {
                            val progress = (currentIndex * 100) / if (total > 0) total else 1
                            prefs.setPrivateSafeExportProgress(progress)
                            prefs.setPrivateSafeExportStatus("Paused (Network Error)")
                            prefs.setPrivateSafeExportPaused(true)
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(android.content.Intent("com.sk.gallery.VAULT_SYNC_COMPLETED"))
                        }
                        return@withContext Result.failure()
                    }
                } finally {
                    if (tempPlainFile.exists()) tempPlainFile.delete()
                    if (tempEncFile.exists()) tempEncFile.delete()
                }
            }

            if (isStopped) {
                return@withContext Result.failure()
            }
            
            setProgress(androidx.work.workDataOf("progress" to 100, "status" to "Export Complete"))
            prefs.setPrivateSafeExportPaused(false)

            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
            val dateStr = sdf.format(java.util.Date())
            val exportLog = "Last Exported on $dateStr - $total of $total files"
            prefs.setPrivateSafeLastExport(exportLog)

            android.util.Log.d("GalleryBySK", "DriveExportWorker: Private Safe export completed successfully")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("GalleryBySK", "DriveExportWorker: Failure in doWork", e)
            Result.failure()
        } finally {
            if (isStopped) {
                val wasPaused = prefs.isPrivateSafeExportPaused()
                withContext(NonCancellable) {
                    if (wasPaused) {
                        val progress = (currentIndex * 100) / if (total > 0) total else 1
                        prefs.setPrivateSafeExportProgress(progress)
                        prefs.setPrivateSafeExportStatus("Paused ($currentIndex of $total)")
                        prefs.setPrivateSafeExportPaused(true)
                        
                        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
                        val dateStr = sdf.format(java.util.Date())
                        val exportLog = "Last Exported on $dateStr - $currentIndex of $total files"
                        prefs.setPrivateSafeLastExport(exportLog)

                        // Force a broadcast so any trailing uploads get their cloud icons updated in the UI
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(android.content.Intent("com.sk.gallery.VAULT_SYNC_COMPLETED"))

                        android.util.Log.d("GalleryBySK", "DriveExportWorker: Stopped / Paused at $currentIndex of $total")
                    } else {
                        android.util.Log.d("GalleryBySK", "DriveExportWorker: Stopped / Cancelled (Not saving progress)")
                    }
                }
            }
        }
    }
}
