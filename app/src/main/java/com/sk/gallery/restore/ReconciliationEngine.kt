package com.sk.gallery.restore

import com.sk.gallery.model.FileEntry
import com.sk.gallery.model.FolderNode
import com.sk.gallery.model.HierarchyIndex
import com.sk.gallery.util.FileUtils

object ReconciliationEngine {

    /**
     * Reconciles local device manifest with cloud manifest.
     * Merges entries, identifying local files, cloud files, and missing local "Ghost" files.
     */
    fun reconcileIndices(
        localIndex: HierarchyIndex?,
        cloudIndex: HierarchyIndex?
    ): HierarchyIndex {
        if (cloudIndex == null && localIndex != null) {
            return localIndex
        }
        if (localIndex == null && cloudIndex != null) {
            // All files in cloud manifest are missing locally (Ghost Cards)
            val ghostEntries = cloudIndex.entries.mapValues { (_, entry) ->
                entry.copy(isMissingLocally = true)
            }
            return cloudIndex.copy(entries = ghostEntries)
        }
        if (localIndex == null && cloudIndex == null) {
            return HierarchyIndex(
                version = 1,
                deviceId = "unknown",
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        }

        val localEntries = localIndex!!.entries
        val cloudEntries = cloudIndex!!.entries
        val mergedEntries = mutableMapOf<String, FileEntry>()
        val folderCounts = mutableMapOf<String, Int>()

        // 1. Process cloud entries (source of truth for full hierarchy)
        for ((hashId, cloudEntry) in cloudEntries) {
            val localEntry = localEntries[hashId]
            val isMissing = (localEntry == null)

            val finalEntry = cloudEntry.copy(
                isMissingLocally = isMissing,
                cloudFileId = cloudEntry.cloudFileId ?: localEntry?.cloudFileId
            )
            mergedEntries[hashId] = finalEntry

            val folderPath = extractFolderPath(cloudEntry.relativePath)
            folderCounts[folderPath] = (folderCounts[folderPath] ?: 0) + 1
        }

        // 2. Add local-only entries that haven't been synced to cloud yet
        for ((hashId, localEntry) in localEntries) {
            if (!mergedEntries.containsKey(hashId)) {
                mergedEntries[hashId] = localEntry.copy(isMissingLocally = false)

                val folderPath = extractFolderPath(localEntry.relativePath)
                folderCounts[folderPath] = (folderCounts[folderPath] ?: 0) + 1
            }
        }

        // 3. Rebuild folder tree nodes
        val folderNodes = folderCounts.map { (relPath, count) ->
            FolderNode(
                relativePath = relPath,
                folderName = FileUtils.extractFolderName(relPath),
                parentPath = FileUtils.extractParentPath(relPath),
                fileCount = count
            )
        }.sortedBy { it.relativePath }

        return HierarchyIndex(
            version = cloudIndex.version,
            deviceId = localIndex.deviceId,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            folderTree = folderNodes,
            entries = mergedEntries
        )
    }

    private fun extractFolderPath(fullRelativePath: String): String {
        val lastSlash = fullRelativePath.lastIndexOf('/')
        return if (lastSlash >= 0) {
            fullRelativePath.substring(0, lastSlash + 1)
        } else {
            "Pictures/"
        }
    }
}
