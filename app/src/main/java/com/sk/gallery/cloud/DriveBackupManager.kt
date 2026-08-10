package com.sk.gallery.cloud

import android.content.Context
import android.util.Log
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.client.googleapis.batch.json.JsonBatchCallback
import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.http.HttpHeaders
import com.sk.gallery.auth.GoogleSignInManager
import com.sk.gallery.util.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.FileInputStream
import com.google.gson.Gson
import com.sk.gallery.model.HierarchyIndex

object DriveBackupManager {

    private const val TAG = "GalleryBySK"
    private const val APP_DATA_FOLDER = "appDataFolder"

    // Uploaded encrypted files go into this subfolder inside appDataFolder
    private const val BACKUP_FOLDER_NAME = "gallery_sk_backup"

    fun getDriveService(context: Context): Drive {
        val account = GoogleSignInManager.getLastSignedInAccount(context)
            ?: throw IllegalStateException("User not signed in")
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("GalleryBySK").build()
    }

    /**
     * Checks how much free Google Drive storage quota is left (in bytes).
     * Returns Long.MAX_VALUE for unlimited accounts (e.g., Google Workspace).
     */
    suspend fun checkStorageQuota(context: Context): Long = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)
        val about = drive.about().get().setFields("storageQuota").execute()
        val quota = about.storageQuota
        if (quota.limit == null) return@withContext Long.MAX_VALUE
        val free = quota.limit - quota.usage
        Log.d(TAG, "DriveBackupManager: Quota check — Limit: ${quota.limit}, Used: ${quota.usage}, Free: $free bytes")
        return@withContext free
    }

    /**
     * Encrypts [localFile] using AES-256-GCM, uploads it to Google Drive's appDataFolder,
     * and returns the resulting Drive file ID.
     *
     * The encrypted file is named "<originalFileName>.enc" to indicate it has been
     * encrypted and should not be opened directly.
     *
     * On success, the temporary encrypted file is deleted from device storage.
     * On failure, the temp file is also cleaned up.
     *
     * @param context Application context
     * @param localFile The original local media file to back up
     * @param driveFileName The name to use in Google Drive (typically "<hash>.enc")
     * @param parentFolderId The target Google Drive directory ID (defaults to appDataFolder root)
     * @param onProgress Called with bytes uploaded so far (best-effort, not guaranteed for small files)
     * @return The Google Drive file ID of the newly uploaded file
     */
    suspend fun uploadEncryptedFile(
        context: Context,
        localFile: java.io.File,
        driveFileName: String,
        parentFolderId: String = APP_DATA_FOLDER,
        onProgress: ((bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)

        // Step 1: Encrypt the file to a temp file in the app's private cache dir
        val encryptedTempFile = java.io.File(context.cacheDir, "${driveFileName}.tmp")
        try {
            FileInputStream(localFile).use { inputStream ->
                BufferedOutputStream(FileOutputStream(encryptedTempFile)).use { outputStream ->
                    CryptoManager.encryptStream(inputStream, outputStream)
                }
            }
            Log.d(TAG, "DriveBackupManager: Encrypted '${localFile.name}' → '${encryptedTempFile.name}' (${encryptedTempFile.length()} bytes)")

            // Step 2: Create Drive file metadata pointing to parentFolderId
            val fileMetadata = File().apply {
                name = driveFileName
                parents = listOf(parentFolderId)
            }

            // Step 3: Upload using FileContent (Drive SDK handles chunked transfer internally)
            val mediaContent = FileContent("application/octet-stream", encryptedTempFile)

            val driveFile = drive.files()
                .create(fileMetadata, mediaContent)
                .setFields("id, name, size")
                .execute()

            val driveFileId = driveFile.id
            Log.d(TAG, "DriveBackupManager: Successfully uploaded '${localFile.name}' → Drive ID: $driveFileId")
            onProgress?.invoke(encryptedTempFile.length(), encryptedTempFile.length())

            return@withContext driveFileId

        } finally {
            // Always clean up the temp encrypted file, whether upload succeeded or failed
            if (encryptedTempFile.exists()) {
                encryptedTempFile.delete()
            }
        }
    }

    suspend fun uploadPlainFile(
        context: Context,
        localFile: java.io.File,
        driveFileName: String,
        parentFolderId: String = APP_DATA_FOLDER,
        onProgress: ((bytesUploaded: Long, totalBytes: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)

        val fileMetadata = File().apply {
            name = driveFileName
            parents = listOf(parentFolderId)
        }

        val mediaContent = FileContent("application/octet-stream", localFile)

        val driveFile = drive.files()
            .create(fileMetadata, mediaContent)
            .setFields("id, name, size")
            .execute()

        val driveFileId = driveFile.id
        Log.d(TAG, "DriveBackupManager: Successfully uploaded plain '${localFile.name}' → Drive ID: $driveFileId")
        onProgress?.invoke(localFile.length(), localFile.length())
        return@withContext driveFileId
    }

    /**
     * Finds a file by exact name inside the given parent folder.
     * Returns its Drive file ID or null if not found.
     */
    suspend fun getFileIdByName(context: Context, fileName: String, parentFolderId: String = APP_DATA_FOLDER): String? = withContext(Dispatchers.IO) {
        return@withContext getFileMetadataByName(context, fileName, parentFolderId)?.id
    }

    /**
     * Finds a file by exact name inside the given parent folder and returns its metadata.
     */
    suspend fun getFileMetadataByName(context: Context, fileName: String, parentFolderId: String = APP_DATA_FOLDER): File? = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)
        val result = drive.files().list()
            .setQ("name = '$fileName' and '$parentFolderId' in parents and trashed = false")
            .setSpaces(APP_DATA_FOLDER)
            .setFields("files(id, name, size)")
            .execute()
        return@withContext result.files.firstOrNull()
    }

    /**
     * Efficiently fetches a Set of all file names currently stored in the given parent folder.
     */
    suspend fun getAllUploadedFileNames(context: Context, parentFolderId: String = APP_DATA_FOLDER): Set<String> = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)
        val fileNames = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            val result = drive.files().list()
                .setQ("'$parentFolderId' in parents and trashed = false")
                .setSpaces(APP_DATA_FOLDER)
                .setFields("nextPageToken, files(name)")
                .setPageToken(pageToken)
                .execute()
            
            result.files?.forEach { file ->
                fileNames.add(file.name)
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)
        
        Log.d(TAG, "DriveBackupManager: Batch fetched ${fileNames.size} file names from Drive.")
        return@withContext fileNames
    }

    /**
     * Finds or creates a subdirectory for the device in the AppData sandbox.
     */
    suspend fun getOrCreateDeviceFolder(context: Context, deviceId: String): String = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)
        
        val query = "name = '$deviceId' and mimeType = 'application/vnd.google-apps.folder' and '$APP_DATA_FOLDER' in parents and trashed = false"
        val result = drive.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ(query)
            .setFields("files(id)")
            .execute()
        
        val existingFolder = result.files?.firstOrNull()
        val folderId = if (existingFolder != null) {
            existingFolder.id
        } else {
            val folderMetadata = File().apply {
                name = deviceId
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(APP_DATA_FOLDER)
            }
            val folder = drive.files().create(folderMetadata).setFields("id").execute()
            folder.id
        }
        createDeviceInfoFile(context, folderId)
        return@withContext folderId
    }

    private suspend fun createDeviceInfoFile(context: Context, parentFolderId: String) = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context)
            // Check if device_info.json already exists in parentFolderId
            val query = "name = 'device_info.json' and '$parentFolderId' in parents and trashed = false"
            val result = drive.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setQ(query)
                .setFields("files(id)")
                .execute()
            if (!result.files.isNullOrEmpty()) {
                return@withContext
            }

            val deviceName = android.os.Build.MANUFACTURER
            val deviceModel = android.os.Build.MODEL
            val infoMap = mapOf(
                "deviceName" to deviceName,
                "deviceModel" to deviceModel
            )
            val json = Gson().toJson(infoMap)
            val tempFile = java.io.File(context.cacheDir, "temp_device_info.json")
            tempFile.writeText(json)

            val fileMetadata = File().apply {
                name = "device_info.json"
                parents = listOf(parentFolderId)
            }
            val mediaContent = com.google.api.client.http.FileContent("application/json", tempFile)
            drive.files().create(fileMetadata, mediaContent).execute()
            tempFile.delete()
            Log.d(TAG, "DriveBackupManager: Created device_info.json in folder $parentFolderId")
        } catch (e: Exception) {
            Log.e(TAG, "DriveBackupManager: Failed to create device_info.json", e)
        }
    }

    /**
     * Finds or creates a Private Safe root directory in appDataFolder for the given device.
     */
    suspend fun getOrCreatePrivateSafeFolder(context: Context, deviceId: String): String = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)
        val folderName = "$deviceId-private_safe"
        
        val query = "name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and 'appDataFolder' in parents and trashed = false"
        val result = drive.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ(query)
            .setFields("files(id)")
            .execute()
        
        val existingFolder = result.files?.firstOrNull()
        if (existingFolder != null) {
            return@withContext existingFolder.id
        }
        
        val folderMetadata = File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(APP_DATA_FOLDER)
        }
        val folder = drive.files().create(folderMetadata).setFields("id").execute()
        createDeviceInfoFile(context, folder.id)
        return@withContext folder.id
    }

    enum class ScanMode {
        REGULAR_ONLY, // For importing in regular gallery
        DELETE_ALL    // For deleting in regular gallery
    }

    /**
     * Scans appDataFolder for all device subdirectories and root, fetches their manifests,
     * and returns metadata for each available backup.
     */
    suspend fun getAvailableBackups(context: Context, scanMode: ScanMode = ScanMode.REGULAR_ONLY): List<BackupInfo> = withContext(Dispatchers.IO) {
        val drive = getDriveService(context)
        val backups = mutableListOf<BackupInfo>()
        val gson = Gson()

        // 1. Scan for subdirectory folders in appDataFolder
        val query = "mimeType = 'application/vnd.google-apps.folder' and '$APP_DATA_FOLDER' in parents and trashed = false"
        val result = drive.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ(query)
            .setFields("files(id, name)")
            .execute()

        val folders = result.files ?: emptyList()
        val deviceIds = folders.map { it.name.removeSuffix("-private_safe") }.distinct()

        for (deviceId in deviceIds) {
            val regularFolder = folders.find { it.name == deviceId }
            val privateSafeFolder = folders.find { it.name == "$deviceId-private_safe" }
            
            if (scanMode == ScanMode.REGULAR_ONLY) {
                if (regularFolder == null) continue // Skip completely if there is no regular backup
            } else {
                if (regularFolder == null && privateSafeFolder == null) continue
            }

            var deviceName = "Unknown"
            var deviceModel = "Device"
            var lastUpdated = System.currentTimeMillis()
            var fileCount = 0
            var actualUploaded = 0
            var manifest: HierarchyIndex? = null

            // Try reading unencrypted device_info.json first
            val folderToQueryForInfo = regularFolder ?: privateSafeFolder
            val infoQuery = "name = 'device_info.json' and '${folderToQueryForInfo!!.id}' in parents and trashed = false"
            val infoResult = drive.files().list().setSpaces(APP_DATA_FOLDER).setQ(infoQuery).setFields("files(id)").execute()
            val infoFile = infoResult.files?.firstOrNull()
            var infoLoaded = false
            if (infoFile != null) {
                val tempInfoFile = java.io.File(context.cacheDir, "temp_info_${deviceId}.json")
                try {
                    if (downloadFile(context, infoFile.id, tempInfoFile)) {
                        val infoJson = tempInfoFile.readText()
                        val infoMap = gson.fromJson(infoJson, Map::class.java)
                        deviceName = infoMap["deviceName"] as? String ?: "Unknown"
                        deviceModel = infoMap["deviceModel"] as? String ?: "Device"
                        infoLoaded = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read device_info.json", e)
                } finally {
                    if (tempInfoFile.exists()) tempInfoFile.delete()
                }
            }

            if (regularFolder != null) {
                val manifestQuery = "name = 'hierarchy_index.json' and '${regularFolder.id}' in parents and trashed = false"
                val manifestResult = drive.files().list().setSpaces(APP_DATA_FOLDER).setQ(manifestQuery).setFields("files(id, name)").execute()
                val mFile = manifestResult.files?.firstOrNull()
                
                if (mFile != null) {
                    val tempFile = java.io.File(context.cacheDir, "temp_scan_${deviceId}.tmp")
                    try {
                        downloadFile(context, mFile.id, tempFile)
                        val manifestJson = tempFile.readText()
                        manifest = gson.fromJson(manifestJson, HierarchyIndex::class.java)

                        if (manifest != null) {
                            if (!infoLoaded) {
                                deviceName = manifest.deviceName ?: "Legacy Device"
                                deviceModel = manifest.deviceModel ?: "Backup"
                            }
                            lastUpdated = manifest.lastUpdatedTimestamp
                            val remoteFileNames = getAllUploadedFileNames(context, regularFolder.id)
                            val validEntries = manifest.entries.values.filter { !it.fileName.endsWith(".json", ignoreCase = true) && !it.fileName.endsWith(".tmp", ignoreCase = true) }
                            actualUploaded = validEntries.count { entry ->
                                remoteFileNames.contains(entry.hashId)
                            }
                            fileCount = validEntries.size
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse manifest for folder ${deviceId}", e)
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
            }

            if (manifest == null) {
                manifest = HierarchyIndex(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    deviceModel = deviceModel,
                    lastUpdatedTimestamp = lastUpdated,
                    entries = emptyMap()
                )
                if (regularFolder != null) {
                    val remoteFiles = getAllUploadedFileNames(context, regularFolder.id)
                    val mediaFiles = remoteFiles.filter { !it.endsWith(".json", ignoreCase = true) && !it.endsWith(".tmp", ignoreCase = true) && !it.startsWith("vault_map") }
                    fileCount = mediaFiles.size
                    actualUploaded = mediaFiles.size
                }
            }
            
            // Add private safe files to the counts if requested
            if (scanMode == ScanMode.DELETE_ALL && privateSafeFolder != null) {
                val psRemoteFiles = getAllUploadedFileNames(context, privateSafeFolder.id)
                val psMediaFiles = psRemoteFiles.filter { !it.endsWith(".json", ignoreCase = true) && !it.endsWith(".tmp", ignoreCase = true) && !it.startsWith("vault_map") }
                fileCount += psMediaFiles.size
                actualUploaded += psMediaFiles.size
            }

            backups.add(BackupInfo(
                deviceId = deviceId,
                deviceFolderId = regularFolder?.id ?: "",
                deviceName = deviceName,
                deviceModel = deviceModel,
                lastUpdatedTimestamp = lastUpdated,
                fileCount = fileCount,
                actualUploadedCount = actualUploaded,
                manifest = manifest!!
            ))
        }

        return@withContext backups
    }

    suspend fun downloadFile(context: Context, fileId: String, destFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context)
            FileOutputStream(destFile).use { out ->
                drive.files().get(fileId).executeMediaAndDownloadTo(out)
            }
            Log.d(TAG, "DriveBackupManager: Downloaded file ID: $fileId to ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DriveBackupManager: Failed to download file ID: $fileId", e)
            if (destFile.exists()) destFile.delete()
            false
        }
    }

    /**
     * Deletes a file from appDataFolder by its Drive file ID.
     */
    suspend fun deleteFile(context: Context, fileId: String) = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context)
            drive.files().delete(fileId).execute()
            Log.d(TAG, "DriveBackupManager: Deleted Drive file ID: $fileId")
        } catch (e: Exception) {
            Log.e(TAG, "DriveBackupManager: Failed to delete Drive file ID: $fileId", e)
        }
    }

    /**
     * Deletes the specific device folder and all its contents.
     */
    suspend fun deleteDeviceFolder(context: Context, deviceId: String) = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context)
            val query = "name = '$deviceId' and mimeType = 'application/vnd.google-apps.folder' and '$APP_DATA_FOLDER' in parents and trashed = false"
            val result = drive.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setQ(query)
                .setFields("files(id)")
                .execute()
            
            val folder = result.files?.firstOrNull() ?: return@withContext
            drive.files().delete(folder.id).execute()
            Log.d(TAG, "DriveBackupManager: Deleted device folder $deviceId and all its contents.")
        } catch (e: Exception) {
            Log.e(TAG, "DriveBackupManager: deleteDeviceFolder failed", e)
        }
    }

    /**
     * Deletes ALL files in appDataFolder (fallback cleanup).
     */
    suspend fun deleteAllBackupFiles(context: Context) = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService(context)
            var pageToken: String? = null
            var totalDeleted = 0
            
            do {
                val result = drive.files().list()
                    .setSpaces(APP_DATA_FOLDER)
                    .setFields("nextPageToken, files(id, name)")
                    .setPageToken(pageToken)
                    .execute()

                val files = result.files ?: emptyList()
                if (files.isNotEmpty()) {
                    val batch = drive.batch()
                    val callback = object : JsonBatchCallback<Void>() {
                        override fun onSuccess(t: Void?, responseHeaders: HttpHeaders?) {}
                        override fun onFailure(e: GoogleJsonError?, responseHeaders: HttpHeaders?) {
                            Log.w(TAG, "DriveBackupManager: Failed to delete a file during batch: ${e?.message}")
                        }
                    }

                    for (file in files) {
                        drive.files().delete(file.id).queue(batch, callback)
                    }
                    
                    batch.execute()
                    totalDeleted += files.size
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)
            
            Log.d(TAG, "DriveBackupManager: Full cancel — batch deleted $totalDeleted files from appDataFolder.")
        } catch (e: Exception) {
            Log.e(TAG, "DriveBackupManager: deleteAllBackupFiles failed", e)
        }
    }
}
data class BackupInfo(
    val deviceId: String,
    val deviceFolderId: String,
    val deviceName: String,
    val deviceModel: String,
    val lastUpdatedTimestamp: Long,
    val fileCount: Int,
    val actualUploadedCount: Int,
    val manifest: HierarchyIndex
)
