package com.sk.gallery.cloud

import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.gson.Gson
import com.sk.gallery.model.HierarchyIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import com.google.api.services.drive.model.File as DriveFile

class DriveVaultManager(private val driveService: Drive) {

    companion object {
        const val APPDATA_FOLDER_ID = "appDataFolder"
        const val MANIFEST_FILE_NAME = "hierarchy_index.json"
        const val CHUNK_SIZE_BYTES = 2 * 1024 * 1024 // 2MB chunk size for resumable uploads
    }

    private val gson = Gson()

    /**
     * Searches for an existing file by name in the hidden appDataFolder sandbox.
     */
    suspend fun findFileInAppData(fileName: String): DriveFile? = withContext(Dispatchers.IO) {
        val query = "'$APPDATA_FOLDER_ID' in parents and name = '$fileName' and trashed = false"
        val result = driveService.files().list()
            .setSpaces(APPDATA_FOLDER_ID)
            .setQ(query)
            .setFields("files(id, name, size, md5Checksum, modifiedTime)")
            .execute()
        return@withContext result.files.firstOrNull()
    }

    /**
     * Efficiently fetches all files currently stored in appDataFolder in batch.
     * Returns a map of fileName -> DriveFile object.
     */
    suspend fun fetchAllAppDataFilesMap(): Map<String, DriveFile> = withContext(Dispatchers.IO) {
        val fileMap = mutableMapOf<String, DriveFile>()
        var pageToken: String? = null
        val query = "'$APPDATA_FOLDER_ID' in parents and trashed = false"

        do {
            val result = driveService.files().list()
                .setSpaces(APPDATA_FOLDER_ID)
                .setQ(query)
                .setPageSize(1000)
                .setPageToken(pageToken)
                .setFields("nextPageToken, files(id, name, size, md5Checksum, modifiedTime)")
                .execute()

            for (file in result.files) {
                fileMap[file.name] = file
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)

        return@withContext fileMap
    }

    /**
     * Uploads or updates the central hierarchy_index.json manifest in appDataFolder.
     */
    suspend fun uploadManifest(manifestFile: File, existingManifestId: String? = null): DriveFile = withContext(Dispatchers.IO) {
        val targetId = existingManifestId ?: findFileInAppData(MANIFEST_FILE_NAME)?.id
        val fileMetadata = DriveFile().apply {
            name = MANIFEST_FILE_NAME
            mimeType = "application/json"
            if (targetId == null) {
                parents = listOf(APPDATA_FOLDER_ID)
            }
        }

        val mediaContent = FileContent("application/json", manifestFile)

        return@withContext if (targetId != null) {
            driveService.files().update(targetId, fileMetadata, mediaContent)
                .setFields("id, name, size, modifiedTime")
                .execute()
        } else {
            driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name, size, modifiedTime")
                .execute()
        }
    }

    /**
     * Downloads and parses hierarchy_index.json from appDataFolder.
     */
    suspend fun downloadManifest(): HierarchyIndex? = withContext(Dispatchers.IO) {
        val remoteManifestFile = findFileInAppData(MANIFEST_FILE_NAME) ?: return@withContext null
        downloadManifestById(remoteManifestFile.id)
    }

    /**
     * Downloads and parses hierarchy_index.json from appDataFolder using fileId.
     */
    suspend fun downloadManifestById(manifestFileId: String): HierarchyIndex? = withContext(Dispatchers.IO) {
        val outputStream = ByteArrayOutputStream()
        driveService.files().get(manifestFileId).executeMediaAndDownloadTo(outputStream)
        val jsonStr = outputStream.toString(Charsets.UTF_8.name())
        return@withContext try {
            gson.fromJson(jsonStr, HierarchyIndex::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Uploads a binary media file (image/video) to appDataFolder using resumable chunked upload.
     * Binary filename is obfuscated to <hashId>.bin.
     */
    suspend fun uploadBinaryResumable(
        localFile: File,
        hashId: String,
        mimeType: String,
        existingCloudFileId: String? = null,
        onProgress: ((Double) -> Unit)? = null
    ): DriveFile = withContext(Dispatchers.IO) {
        val obfuscatedName = "$hashId.bin"
        val targetId = existingCloudFileId ?: findFileInAppData(obfuscatedName)?.id

        val fileMetadata = DriveFile().apply {
            name = obfuscatedName
            this.mimeType = mimeType
            if (targetId == null) {
                parents = listOf(APPDATA_FOLDER_ID)
            }
        }

        val mediaContent = FileContent(mimeType, localFile)

        val request = if (targetId != null) {
            driveService.files().update(targetId, fileMetadata, mediaContent)
        } else {
            driveService.files().create(fileMetadata, mediaContent)
        }

        val uploader: MediaHttpUploader = request.mediaHttpUploader
        uploader.isDirectUploadEnabled = false // Enable resumable upload
        uploader.chunkSize = CHUNK_SIZE_BYTES
        uploader.setProgressListener { uploaderRef ->
            when (uploaderRef.uploadState) {
                MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS -> {
                    onProgress?.invoke(uploaderRef.progress)
                }
                MediaHttpUploader.UploadState.MEDIA_COMPLETE -> {
                    onProgress?.invoke(1.0)
                }
                else -> {}
            }
        }

        request.fields = "id, name, size, md5Checksum"
        return@withContext request.execute()
    }
}
