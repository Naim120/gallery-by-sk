package com.sk.gallery.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DriveImportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = com.sk.gallery.data.local.AppPreferences(applicationContext)
        var total = 0
        var currentIndex = 0
        var successCount = 0
        var deviceLabel = "Root Folder"
        var deviceFolderId = inputData.getString("device_folder_id")
        try {
            val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(applicationContext)
                ?: return@withContext Result.failure()

            val driveManager = GoogleDriveManager(applicationContext, account)
            val passphrase = prefs.getCloudPassphrase() ?: return@withContext Result.failure()

            // Retrieve input device folder ID or scan to resolve it automatically
            if (deviceFolderId.isNullOrEmpty()) {
                deviceFolderId = "appDataFolder"
            }

            var targetFolderId = deviceFolderId
            
            var deviceName = "Unknown"
            var deviceModel = "Device"
            if (deviceFolderId != null && !deviceFolderId.equals("appDataFolder", ignoreCase = true)) {
                try {
                    val drive = com.sk.gallery.cloud.DriveBackupManager.getDriveService(applicationContext)
                    val infoQuery = "name = 'device_info.json' and '$deviceFolderId' in parents and trashed = false"
                    val infoResult = drive.files().list()
                        .setSpaces("appDataFolder")
                        .setQ(infoQuery)
                        .setFields("files(id)")
                        .execute()
                    val infoFile = infoResult.files?.firstOrNull()
                    if (infoFile != null) {
                        val tempInfoFile = java.io.File(applicationContext.cacheDir, "temp_worker_info.json")
                        if (com.sk.gallery.cloud.DriveBackupManager.downloadFile(applicationContext, infoFile.id, tempInfoFile)) {
                            val infoJson = tempInfoFile.readText()
                            val infoMap = com.google.gson.Gson().fromJson(infoJson, Map::class.java)
                            deviceName = infoMap["deviceName"] as? String ?: "Unknown"
                            deviceModel = infoMap["deviceModel"] as? String ?: "Device"
                        }
                        tempInfoFile.delete()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "DriveImportWorker: Failed to read device info", e)
                }
            }

            deviceLabel = if (deviceName.equals(deviceModel, ignoreCase = true)) {
                deviceName
            } else if (deviceFolderId != null && deviceFolderId.equals("appDataFolder", ignoreCase = true)) {
                "Root Folder"
            } else {
                "$deviceName $deviceModel"
            }

            val finalFolderId = targetFolderId ?: "appDataFolder"
            val existingFiles = driveManager.listEncryptedFiles(finalFolderId)
            val vaultDir = applicationContext.getDir("PrivateVault", Context.MODE_PRIVATE)

            // Process vault_map.json.enc first if it exists to ensure proper filenames mapping
            val mapFile = existingFiles.find { it.name == "vault_map.json.enc" }
            if (mapFile != null) {
                val tempEncMap = java.io.File(applicationContext.cacheDir, "temp_vault_map.json.enc")
                val tempPlainMap = java.io.File(applicationContext.cacheDir, "temp_vault_map.json")
                var mapDecrypted = false
                try {
                    if (driveManager.downloadFile(mapFile.id, tempEncMap)) {
                        mapDecrypted = com.sk.gallery.data.crypto.CryptoManager.decryptFromCloud(tempEncMap, tempPlainMap, passphrase)
                        if (mapDecrypted) {
                            val gson = com.google.gson.Gson()
                            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                            val incomingMap: Map<String, String> = gson.fromJson(tempPlainMap.readText(), type) ?: emptyMap()
                            com.sk.gallery.data.PrivateVaultManager.mergeMap(applicationContext, incomingMap)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "DriveImportWorker: Error decrypting/merging vault_map", e)
                } finally {
                    if (tempEncMap.exists()) tempEncMap.delete()
                    if (tempPlainMap.exists()) tempPlainMap.delete()
                }
            }

            val mediaFiles = existingFiles.filter { it.name.endsWith(".enc") && it.name != "vault_map.json.enc" }
            total = mediaFiles.size
            if (total == 0) {
                setProgress(androidx.work.workDataOf("progress" to 100, "status" to "Import Complete"))
                
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
                val dateStr = sdf.format(java.util.Date())
                val importLog = "Last Imported on $dateStr - 0 of 0 files - $deviceLabel"
                prefs.setPrivateSafeLastImport(importLog)

                val outputData = androidx.work.workDataOf("imported_device_folder_id" to deviceFolderId)
                return@withContext Result.success(outputData)
            }

            android.util.Log.d("GalleryBySK", "DriveImportWorker: Starting Private Safe import from $targetFolderId. Total remote media files: $total")

            for ((index, cloudFile) in mediaFiles.withIndex()) {
                currentIndex = index
                if (isStopped) {
                    break
                }

                val progress = (index * 100) / total
                setProgress(androidx.work.workDataOf("progress" to progress, "status" to "Restoring $index of $total"))
                android.util.Log.d("GalleryBySK", "DriveImportWorker: Progress: $progress% ($index of $total) - Restoring: ${cloudFile.name}")

                val originalHashId = cloudFile.name.removeSuffix(".enc")
                val destFile = java.io.File(vaultDir, originalHashId)
                if (destFile.exists()) {
                    successCount++
                    continue // Already exists locally
                }

                val tempEncFile = java.io.File(applicationContext.cacheDir, "temp_download_$originalHashId.enc")
                val tempPlainFile = java.io.File(applicationContext.cacheDir, "temp_plain_$originalHashId")
                
                var fileDecrypted = false
                try {
                    if (driveManager.downloadFile(cloudFile.id, tempEncFile)) {
                        // 1. Decrypt from cloud to get the raw Plaintext
                        if (com.sk.gallery.data.crypto.CryptoManager.decryptFromCloud(tempEncFile, tempPlainFile, passphrase)) {
                            
                            // 2. Encrypt the raw Plaintext with this device's local hardware key
                            com.sk.gallery.data.crypto.CryptoManager.encryptFileLocal(tempPlainFile, destFile)
                            
                            // 3. Generate encrypted thumbnail from plaintext file
                            try {
                                val thumbFile = java.io.File(vaultDir, "$originalHashId.thumb")
                                com.sk.gallery.data.PrivateVaultManager.generateAndEncryptThumbnail(applicationContext, tempPlainFile, thumbFile)
                            } catch (e: Exception) {
                                android.util.Log.e("GalleryBySK", "Failed to generate thumbnail for $originalHashId", e)
                            }
                            
                            // 4. Extract and save video duration if applicable
                            val isVideo = originalHashId.endsWith(".mp4", true) || originalHashId.endsWith(".mkv", true) || originalHashId.endsWith(".mov", true)
                            if (isVideo) {
                                com.sk.gallery.data.PrivateVaultManager.saveVideoDuration(applicationContext, originalHashId, tempPlainFile)
                            }
                            
                            fileDecrypted = true
                            successCount++
                            
                            // Add to cloudStatusSet so the cloud badge shows up immediately
                            val currentStatus = com.sk.gallery.data.PrivateVaultManager.cloudStatusSet.toMutableSet()
                            currentStatus.add(cloudFile.name)
                            com.sk.gallery.data.PrivateVaultManager.setCloudStatus(applicationContext, currentStatus)
                            
                            // 5. Broadcast so UI updates immediately
                            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(android.content.Intent("com.sk.gallery.VAULT_SYNC_COMPLETED"))
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "DriveImportWorker: Error downloading/decrypting $originalHashId", e)
                    if (e is java.io.IOException) {
                        android.util.Log.e("GalleryBySK", "DriveImportWorker: Network error. Pausing import.")
                        withContext(NonCancellable) {
                            val progress = (index * 100) / if (total > 0) total else 1
                            prefs.setPrivateSafeImportProgress(progress)
                            prefs.setPrivateSafeImportStatus("Paused (Network Error)")
                            prefs.setPrivateSafeImportPaused(true)
                            
                            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
                            val dateStr = sdf.format(java.util.Date())
                            val importLog = "Last Imported on $dateStr - $successCount of $total files - $deviceLabel"
                            prefs.setPrivateSafeLastImport(importLog)
                        }
                        return@withContext Result.failure()
                    }
                } finally {
                    if (tempEncFile.exists()) tempEncFile.delete()
                    if (tempPlainFile.exists()) tempPlainFile.delete()
                    if (!fileDecrypted && destFile.exists()) destFile.delete()
                }
            }

            if (isStopped) {
                return@withContext Result.failure()
            }

            // Force reload in-memory cache of vault_map.json
            com.sk.gallery.data.PrivateVaultManager.reloadMap(applicationContext)

            setProgress(androidx.work.workDataOf("progress" to 100, "status" to "Import Complete"))
            prefs.setPrivateSafeImportPaused(false)
            prefs.addImportedVault(deviceFolderId ?: "appDataFolder")

            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
            val dateStr = sdf.format(java.util.Date())
            val importLog = "Last Imported on $dateStr - $successCount of $total files - $deviceLabel"
            prefs.setPrivateSafeLastImport(importLog)

            android.util.Log.d("GalleryBySK", "DriveImportWorker: Private Safe import completed successfully. Restored: $successCount files")
            val outputData = androidx.work.workDataOf("imported_device_folder_id" to deviceFolderId)
            Result.success(outputData)
        } catch (e: Exception) {
            android.util.Log.e("GalleryBySK", "DriveImportWorker: Failure in doWork", e)
            Result.failure()
        } finally {
            if (isStopped) {
                withContext(NonCancellable) {
                    val progress = (currentIndex * 100) / if (total > 0) total else 1
                    prefs.setPrivateSafeImportProgress(progress)
                    prefs.setPrivateSafeImportStatus("Paused ($currentIndex of $total)")
                    prefs.setPrivateSafeImportPaused(true)
                    
                    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy h:mm a", java.util.Locale.US)
                    val dateStr = sdf.format(java.util.Date())
                    val importLog = "Last Imported on $dateStr - $successCount of $total files - $deviceLabel"
                    prefs.setPrivateSafeLastImport(importLog)

                    android.util.Log.d("GalleryBySK", "DriveImportWorker: Stopped / Paused at $currentIndex of $total")
                }
            }
        }
    }
}
