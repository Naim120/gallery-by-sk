package com.sk.gallery.data

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sk.gallery.model.FileEntry
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object PrivateVaultManager {

    private const val TAG = "GalleryBySK"
    private const val VAULT_DIR_NAME = "PrivateVault"
    private const val MAP_FILE_NAME = "vault_map.json"

    // Maps internal filename -> original absolute path
    private var vaultMap: MutableMap<String, String> = mutableMapOf()
    private var vaultMetadata: MutableMap<String, Long> = mutableMapOf()
    private var isMapLoaded = false
    private val gson = Gson()
    
    var cloudStatusSet: MutableSet<String> = mutableSetOf()
    private const val CLOUD_STATUS_FILE_NAME = "vault_cloud_status.json"
    private const val METADATA_FILE_NAME = "vault_metadata.json"
    
    private val vaultMutex = ReentrantLock()

    private fun getVaultDir(context: Context): File {
        val dir = context.getDir(VAULT_DIR_NAME, Context.MODE_PRIVATE)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadCloudStatus(context: Context) {
        vaultMutex.withLock {
            val file = File(getVaultDir(context), CLOUD_STATUS_FILE_NAME)
            if (file.exists()) {
                try {
                    val type = object : TypeToken<MutableSet<String>>() {}.type
                    cloudStatusSet = gson.fromJson(file.readText(), type) ?: mutableSetOf()
                } catch (e: Exception) {}
            }
        }
    }

    fun setCloudStatus(context: Context, uploadedFileNames: Set<String>) {
        vaultMutex.withLock {
            cloudStatusSet = uploadedFileNames.toMutableSet()
            val file = File(getVaultDir(context), CLOUD_STATUS_FILE_NAME)
            try {
                file.writeText(gson.toJson(cloudStatusSet))
            } catch (e: Exception) {}
        }
    }

    private fun loadMap(context: Context) {
        vaultMutex.withLock {
            if (isMapLoaded) return
            val mapFile = File(getVaultDir(context), MAP_FILE_NAME)
            if (mapFile.exists()) {
                try {
                    val json = mapFile.readText()
                    val type = object : TypeToken<MutableMap<String, String>>() {}.type
                    vaultMap = gson.fromJson(json, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading vault map", e)
                }
            }
            
            val metaFile = File(getVaultDir(context), METADATA_FILE_NAME)
            if (metaFile.exists()) {
                try {
                    val json = metaFile.readText()
                    val type = object : TypeToken<MutableMap<String, Long>>() {}.type
                    vaultMetadata = gson.fromJson(json, type) ?: mutableMapOf()
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading vault metadata", e)
                }
            }
            
            isMapLoaded = true
        }
    }

    fun reloadMap(context: Context) {
        vaultMutex.withLock {
            isMapLoaded = false
            loadMap(context)
        }
    }

    fun mergeMap(context: Context, incomingMap: Map<String, String>) {
        vaultMutex.withLock {
            loadMap(context)
            vaultMap.putAll(incomingMap)
            saveMap(context)
        }
    }

    private fun saveMap(context: Context) {
        // Assume vaultMutex is already held by the caller
        val mapFile = File(getVaultDir(context), MAP_FILE_NAME)
        val metaFile = File(getVaultDir(context), METADATA_FILE_NAME)
        try {
            val json = gson.toJson(vaultMap)
            mapFile.writeText(json)
            
            val metaJson = gson.toJson(vaultMetadata)
            metaFile.writeText(metaJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving vault map", e)
        }
    }

    fun moveToVault(context: Context, entries: List<FileEntry>, onSuccess: () -> Unit) {
        val total = entries.size
        var progressDialog: android.app.AlertDialog? = null
        var progressBar: android.widget.ProgressBar? = null
        var tvProgress: android.widget.TextView? = null
        
        try {
            progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            progressBar.max = total
            progressBar.progress = 0
            
            tvProgress = android.widget.TextView(context).apply {
                text = "Moving 0 of $total..."
                setPadding(16, 16, 16, 16)
            }
            
            val layout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
                addView(tvProgress)
                addView(progressBar)
            }
            
            progressDialog = android.app.AlertDialog.Builder(context)
                .setTitle("Private Safe")
                .setView(layout)
                .setCancelable(false)
                .create()
                
            progressDialog.show()
        } catch (e: Exception) {
            // Fails if context is not an Activity, fallback to silent background operation
        }

        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            loadMap(context)
            val vaultDir = getVaultDir(context)
            var movedCount = 0
            val pathsToScan = mutableListOf<String>()
            val successfulEntries = mutableListOf<FileEntry>()

            for (entry in entries) {
                val srcFile = File(Environment.getExternalStorageDirectory(), entry.relativePath)
                if (!srcFile.exists()) continue

                val extension = entry.fileName.substringAfterLast('.', "")
                val newName = UUID.randomUUID().toString() + if (extension.isNotEmpty()) ".$extension" else ""
                val destFile = File(vaultDir, newName)

                val isVideo = entry.mimeType.startsWith("video", true)
                var success = false
                
                // Get duration before encrypting and moving
                val duration = if (isVideo) getVideoDuration(srcFile) else 0L
                
                try {
                    val originalLastModified = srcFile.lastModified()
                    com.sk.gallery.data.crypto.CryptoManager.encryptFileLocal(srcFile, destFile)
                    destFile.setLastModified(originalLastModified)
                    generateAndEncryptThumbnail(context, srcFile, File(vaultDir, "$newName.thumb"))

                    if (srcFile.delete()) {
                        success = true
                    } else {
                        try {
                            val uri = if (isVideo) {
                                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            } else {
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            }
                            context.contentResolver.delete(uri, "${android.provider.MediaStore.MediaColumns.DATA} = ?", arrayOf(srcFile.absolutePath))
                            if (!srcFile.exists()) success = true
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error moving to vault", e)
                }

                if (success) {
                    vaultMutex.withLock {
                        vaultMap[newName] = srcFile.absolutePath
                        vaultMetadata[newName] = duration
                        if (entry.cloudFileId != null) {
                            cloudStatusSet.add("$newName.enc")
                        }
                    }
                    pathsToScan.add(srcFile.absolutePath)
                    successfulEntries.add(entry)
                    
                    movedCount++
                    
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressBar?.progress = movedCount
                        tvProgress?.text = "Moving $movedCount of $total..."
                    }
                } else {
                    if (destFile.exists()) destFile.delete()
                    val thumbFile = File(vaultDir, "$newName.thumb")
                    if (thumbFile.exists()) thumbFile.delete()
                }
            }

            if (movedCount > 0) {
                vaultMutex.withLock {
                    saveMap(context)
                    setCloudStatus(context, cloudStatusSet)
                }
                android.media.MediaScannerConnection.scanFile(context, pathsToScan.toTypedArray(), null) { _, _ -> }
                com.sk.gallery.data.MediaRepository.getInstance(context).removeEntriesInstantly(successfulEntries)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog?.dismiss()
                    onSuccess()
                }
            }
        }
    }

    fun restoreFromVault(context: Context, internalFileName: String, targetDir: File? = null, onSuccess: () -> Unit) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            loadMap(context)
            val vaultDir = getVaultDir(context)
            val srcFile = File(vaultDir, internalFileName)
            if (!srcFile.exists()) return@launch

            val originalPath = vaultMutex.withLock { vaultMap[internalFileName] } ?: return@launch
            
            val destFile = if (targetDir != null) {
                val originalName = File(originalPath).name
                File(targetDir, originalName)
            } else {
                File(originalPath)
            }

            destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            try {
                val originalLastModified = srcFile.lastModified()
                val tempFile = File(destFile.absolutePath + ".tmp")
                com.sk.gallery.data.crypto.CryptoManager.decryptFileLocal(srcFile, tempFile)
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    if (destFile.exists()) destFile.delete()
                    tempFile.renameTo(destFile)
                    destFile.setLastModified(originalLastModified)
                }

                if (srcFile.delete()) {
                    val thumbFile = File(vaultDir, "$internalFileName.thumb")
                    if (thumbFile.exists()) thumbFile.delete()
                    
                    val cloudRemoved = vaultMutex.withLock {
                        vaultMap.remove(internalFileName)
                        vaultMetadata.remove(internalFileName)
                        saveMap(context)
                        cloudStatusSet.remove("${internalFileName}.enc")
                    }
                    
                    if (cloudRemoved) {
                        setCloudStatus(context, cloudStatusSet)
                        
                        // Scenario 3 & 4: Ensure the file gets a cloud icon in the regular gallery
                        // We must compute the exact hashId that MediaStoreScanner will generate for this file.
                        // MediaStoreScanner hashes the relative path (e.g. "DCIM/Camera/file.jpg").
                        // originalPath is absolute (e.g. "/storage/emulated/0/DCIM/Camera/file.jpg").
                        val externalStoragePath = android.os.Environment.getExternalStorageDirectory().absolutePath + "/"
                        if (destFile.absolutePath.startsWith(externalStoragePath)) {
                            val relativePath = destFile.absolutePath.substring(externalStoragePath.length)
                            val hashId = com.sk.gallery.util.FileUtils.hashStringSha256(relativePath)
                            
                            // Delay briefly so MediaScanner finishes creating the manifest entry first
                            kotlinx.coroutines.GlobalScope.launch {
                                kotlinx.coroutines.delay(1000)
                                com.sk.gallery.data.MediaRepository.getInstance(context).setCloudStatusForFile(hashId, "restored_from_vault")
                            }
                        }
                    }
                    
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null) { _, _ -> }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onSuccess()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring from vault", e)
            }
        }
    }

    fun deleteFromVault(context: Context, internalFileName: String): Boolean {
        loadMap(context)
        val vaultDir = getVaultDir(context)
        val file = File(vaultDir, internalFileName)
        var success = false
        if (file.exists()) {
            success = file.delete()
        }
        val thumbFile = File(vaultDir, "$internalFileName.thumb")
        if (thumbFile.exists()) thumbFile.delete()
        
        vaultMutex.withLock {
            vaultMap.remove(internalFileName)
            vaultMetadata.remove(internalFileName)
            saveMap(context)
            
            // Remove from cloud status if deleted
            if (cloudStatusSet.remove("${internalFileName}.enc")) {
                setCloudStatus(context, cloudStatusSet)
            }
        }
        
        return success
    }

    fun saveVideoDuration(context: Context, internalFileName: String, fileForDuration: File) {
        val duration = getVideoDuration(fileForDuration)
        if (duration > 0) {
            vaultMutex.withLock {
                vaultMetadata[internalFileName] = duration
                saveMap(context)
            }
        }
    }

    private fun getVideoDuration(file: File): Long {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            return time?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving video duration for ${file.name}", e)
            return 0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
    }

    fun getVaultEntries(context: Context): List<FileEntry> {
        loadMap(context)
        loadCloudStatus(context)
        val vaultDir = getVaultDir(context)
        val list = mutableListOf<FileEntry>()

        val files = vaultDir.listFiles() ?: return emptyList()

        for (file in files) {
            if (file.name == MAP_FILE_NAME || file.name == CLOUD_STATUS_FILE_NAME || file.name == METADATA_FILE_NAME || file.name.endsWith(".thumb")) continue
            val originalPath = vaultMap[file.name] ?: continue
            val originalName = File(originalPath).name

            val isVideo = originalName.endsWith(".mp4", true) || originalName.endsWith(".mkv", true) || originalName.endsWith(".mov", true)
            val mimeType = if (isVideo) "video/*" else "image/*"

            val durationVal = vaultMetadata[file.name] ?: 0L
            val cloudFileId = if (cloudStatusSet.contains("${file.name}.enc")) "uploaded" else null

            list.add(
                FileEntry(
                    hashId = file.name,
                    relativePath = file.absolutePath,
                    fileName = originalName,
                    mimeType = mimeType,
                    sizeBytes = file.length(),
                    sha256Checksum = "", 
                    dateModified = file.lastModified() / 1000L,
                    duration = durationVal,
                    cloudFileId = cloudFileId
                )
            )
        }
        return list.sortedByDescending { it.dateModified }
    }
    
    fun generateAndEncryptThumbnail(context: Context, srcFile: File, destThumbFile: File) {
        val tempThumb = File(context.cacheDir, "temp_thumb_${UUID.randomUUID()}.jpg")
        try {
            val bitmap = com.bumptech.glide.Glide.with(context)
                .asBitmap()
                .load(srcFile)
                .submit(512, 512)
                .get()
                
            tempThumb.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
            com.sk.gallery.data.crypto.CryptoManager.encryptFileLocal(tempThumb, destThumbFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for ${srcFile.name}", e)
        } finally {
            if (tempThumb.exists()) tempThumb.delete()
        }
    }
}
