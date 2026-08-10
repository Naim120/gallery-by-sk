package com.sk.gallery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_queue")
data class ImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileHash: String,
    val expectedSizeBytes: Long,
    val relativePath: String, // Where it should go e.g. "Pictures/Gallery"
    val dateModified: Long, // Original timestamp in seconds
    val status: String = STATUS_PENDING,
    val driveFileId: String? = null,
    val deviceFolderId: String = "appDataFolder",
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_PAUSED = "PAUSED"
    }
}
