package com.sk.gallery.restore

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.api.services.drive.Drive
import com.sk.gallery.cloud.DriveVaultManager
import com.sk.gallery.model.FileEntry
import com.sk.gallery.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class RestorationManager(
    private val context: Context,
    private val driveService: Drive
) {

    private val vaultManager = DriveVaultManager(driveService)

    /**
     * Restores a missing file from appDataFolder to its exact local relative path.
     * Verifies SHA-256 checksum after download.
     */
    suspend fun restoreFile(entry: FileEntry): Boolean = withContext(Dispatchers.IO) {
        val obfuscatedName = "${entry.hashId}.bin"
        val cloudFile = if (entry.cloudFileId != null) {
            driveService.files().get(entry.cloudFileId).execute()
        } else {
            vaultManager.findFileInAppData(obfuscatedName)
        } ?: return@withContext false

        // Step 1: Download binary content to temp location
        val tempDir = File(context.cacheDir, "restore_temp").apply { mkdirs() }
        val tempFile = File(tempDir, "${entry.hashId}.tmp")

        try {
            FileOutputStream(tempFile).use { outputStream ->
                driveService.files().get(cloudFile.id).executeMediaAndDownloadTo(outputStream)
            }

            // Step 2: Post-download SHA-256 integrity verification
            val downloadedChecksum = FileUtils.calculateSha256(tempFile)
            if (entry.sha256Checksum.isNotEmpty() && !downloadedChecksum.equals(entry.sha256Checksum, ignoreCase = true)) {
                tempFile.delete()
                throw SecurityException("Post-download SHA-256 checksum mismatch for ${entry.fileName}")
            }

            // Step 3: Physically write file to exact local relative path
            val targetFile = writeToExactLocalPath(tempFile, entry)

            // Step 4: Trigger MediaStore scan
            if (targetFile != null && targetFile.exists()) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(entry.mimeType)
                ) { path, uri ->
                    if (uri != null && path == targetFile.absolutePath) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DATE_MODIFIED, entry.dateModified)
                            put(MediaStore.MediaColumns.DATE_ADDED, entry.dateModified)
                        }
                        try {
                            context.contentResolver.update(uri, values, null, null)
                        } catch (e: Exception) {
                            android.util.Log.w("GalleryBySK", "Failed to update MediaStore timestamps for $path")
                        }
                    }
                }
                return@withContext true
            }

            return@withContext false
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun writeToExactLocalPath(sourceFile: File, entry: FileEntry): File? {
        var targetFileName = entry.fileName

        val parentDirStr = File(entry.relativePath).parent ?: "Pictures"
        val relativePathClean = parentDirStr.replace("\\", "/").removeSuffix("/")

        val externalStorage = Environment.getExternalStorageDirectory()
        val destFile = File(externalStorage, "$relativePathClean/$targetFileName")

        try {
            destFile.parentFile?.mkdirs()
            sourceFile.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            val success = destFile.setLastModified(entry.dateModified * 1000L)
            android.util.Log.d("GalleryBySK", "RestorationManager: setLastModified physically on file: $success to ${entry.dateModified}")
            return destFile
        } catch (e: Exception) {
            android.util.Log.e("GalleryBySK", "RestorationManager: Failed to write file directly to $destFile", e)
            return null
        }
    }


}
