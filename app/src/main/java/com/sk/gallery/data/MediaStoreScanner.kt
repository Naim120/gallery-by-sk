package com.sk.gallery.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sk.gallery.model.FileEntry
import com.sk.gallery.model.FolderNode
import com.sk.gallery.model.HierarchyIndex
import com.sk.gallery.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.io.FileWriter

class MediaStoreScanner(private val context: Context) {

    companion object {
        private const val TAG = "GalleryBySK"
        // Serializes all reads and writes to hierarchy_index.json
        // to prevent concurrent access corruption (EOFException, MalformedJsonException)
        private val manifestMutex = Mutex()
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val manifestFileName = "hierarchy_index.json"

    fun getLocalManifestFile(): File {
        return File(context.filesDir, manifestFileName)
    }

    suspend fun scanMediaStore(): HierarchyIndex = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "MediaStoreScanner: Starting scan of local media...")

        val oldManifest = try {
            manifestMutex.withLock {
                loadLocalManifestInternal()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStoreScanner: Failed to load old manifest", e)
            null
        }
        val oldEntriesMap = oldManifest?.entries ?: emptyMap()

        val entriesMap = mutableMapOf<String, FileEntry>()
        val folderCounts = mutableMapOf<String, Int>()

        val deviceId = getDeviceId(context)

        // Query Images
        scanUri(
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            entriesMap = entriesMap,
            oldEntriesMap = oldEntriesMap,
            folderCounts = folderCounts
        )

        // Query Videos
        scanUri(
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            entriesMap = entriesMap,
            oldEntriesMap = oldEntriesMap,
            folderCounts = folderCounts
        )

        // Build folder tree nodes
        val folderTree = folderCounts.map { (relPath, count) ->
            FolderNode(
                relativePath = relPath,
                folderName = FileUtils.extractFolderName(relPath),
                parentPath = FileUtils.extractParentPath(relPath),
                fileCount = count
            )
        }.sortedBy { it.relativePath }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "MediaStoreScanner: Scan complete in ${duration}ms! Found ${entriesMap.size} media files across ${folderTree.size} folders.")

        val hierarchyIndex = HierarchyIndex(
            version = 1,
            deviceId = deviceId,
            deviceName = android.os.Build.MANUFACTURER,
            deviceModel = android.os.Build.MODEL,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            folderTree = folderTree,
            entries = entriesMap
        )

        // saveManifestLocally acquires the mutex itself
        saveManifestLocally(hierarchyIndex)
        return@withContext hierarchyIndex
    }

    private fun scanUri(
        contentUri: Uri,
        entriesMap: MutableMap<String, FileEntry>,
        oldEntriesMap: Map<String, FileEntry>,
        folderCounts: MutableMap<String, Int>
    ) {
        val resolver: ContentResolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.MediaColumns.RELATIVE_PATH
            } else {
                MediaStore.MediaColumns.DATA
            },
            MediaStore.Video.VideoColumns.DURATION
        )

        resolver.query(contentUri, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val fileName = cursor.getString(nameColumn) ?: "media_$id"
                val mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream"
                val sizeBytes = cursor.getLong(sizeColumn)
                val dateModified = cursor.getLong(dateColumn)
                val rawPath = cursor.getString(pathColumn)
                
                val durationColumn = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val duration = if (durationColumn >= 0) cursor.getLong(durationColumn) else null
                // Treat 0 duration as null for cleaner checks later
                val finalDuration = if (duration != null && duration > 0) duration else null

                val relativeFolderPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    FileUtils.normalizeRelativePath(rawPath)
                } else {
                    extractRelativeFromDataPath(rawPath)
                }

                val fullRelativePath = "$relativeFolderPath$fileName"
                val hashId = FileUtils.hashStringSha256(fullRelativePath)

                // Fast metadata hash to avoid reading 3,000 file input streams during UI scan
                val fastSha256 = FileUtils.hashStringSha256("$fullRelativePath:$sizeBytes:$dateModified")

                val cloudFileId = oldEntriesMap[hashId]?.cloudFileId

                val entry = FileEntry(
                    hashId = hashId,
                    relativePath = fullRelativePath,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = sizeBytes,
                    sha256Checksum = fastSha256,
                    dateModified = dateModified,
                    duration = finalDuration,
                    cloudFileId = cloudFileId,
                    isMissingLocally = false
                )

                entriesMap[hashId] = entry
                folderCounts[relativeFolderPath] = (folderCounts[relativeFolderPath] ?: 0) + 1
            }
        }
    }

    private fun extractRelativeFromDataPath(dataPath: String?): String {
        if (dataPath.isNullOrEmpty()) return "Pictures/"
        val lower = dataPath.lowercase()
        val index = lower.indexOf("/storage/emulated/0/")
        return if (index >= 0) {
            val rel = dataPath.substring(index + "/storage/emulated/0/".length)
            val lastSlash = rel.lastIndexOf('/')
            if (lastSlash >= 0) rel.substring(0, lastSlash + 1) else "Pictures/"
        } else {
            "Pictures/"
        }
    }

    private fun saveManifestLocallyInternal(manifest: HierarchyIndex) {
        val file = getLocalManifestFile()
        try {
            val tempFile = File(context.filesDir, "hierarchy_index.tmp")
            FileWriter(tempFile).use { writer ->
                gson.toJson(manifest, writer)
                writer.flush()
            }
            tempFile.renameTo(file)
            Log.d(TAG, "MediaStoreScanner: Local manifest saved successfully to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "MediaStoreScanner: Failed to save manifest", e)
        }
    }

    suspend fun saveManifestLocally(manifest: HierarchyIndex) {
        manifestMutex.withLock {
            saveManifestLocallyInternal(manifest)
        }
    }

    private fun loadLocalManifestInternal(): HierarchyIndex? {
        val file = getLocalManifestFile()
        if (!file.exists()) return null
        return try {
            FileReader(file).use { reader ->
                gson.fromJson(reader, HierarchyIndex::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStoreScanner: Failed to read local manifest file", e)
            file.delete()
            null
        }
    }

    suspend fun loadLocalManifest(): HierarchyIndex? {
        return manifestMutex.withLock {
            loadLocalManifestInternal()
        }
    }

    suspend fun updateManifestLocally(updateBlock: (HierarchyIndex?) -> HierarchyIndex?): HierarchyIndex? {
        return manifestMutex.withLock {
            val currentManifest = loadLocalManifestInternal()
            val newManifest = updateBlock(currentManifest)
            if (newManifest != null) {
                saveManifestLocallyInternal(newManifest)
            }
            newManifest
        }
    }

    private fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        } catch (e: Exception) {
            "unknown_device"
        }
    }
}
