package com.sk.gallery.model

import com.google.gson.annotations.SerializedName

data class HierarchyIndex(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("deviceModel") val deviceModel: String? = null,
    @SerializedName("lastUpdatedTimestamp") val lastUpdatedTimestamp: Long = System.currentTimeMillis(),
    @SerializedName("folderTree") val folderTree: List<FolderNode> = emptyList(),
    @SerializedName("entries") val entries: Map<String, FileEntry> = emptyMap()
)

data class FolderNode(
    @SerializedName("relativePath") val relativePath: String,
    @SerializedName("folderName") val folderName: String,
    @SerializedName("parentPath") val parentPath: String? = null,
    @SerializedName("fileCount") val fileCount: Int = 0
)

data class FileEntry(
    @SerializedName("hashId") val hashId: String,
    @SerializedName("relativePath") val relativePath: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("sizeBytes") val sizeBytes: Long,
    @SerializedName("sha256Checksum") val sha256Checksum: String,
    @SerializedName("dateModified") val dateModified: Long,
    @SerializedName("duration") val duration: Long? = null,
    @SerializedName("cloudFileId") val cloudFileId: String? = null,
    @SerializedName("isMissingLocally") val isMissingLocally: Boolean = false
)
