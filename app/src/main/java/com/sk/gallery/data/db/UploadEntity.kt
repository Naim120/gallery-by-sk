package com.sk.gallery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_queue")
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val fileHash: String,
    val fileSizeBytes: Long,
    val status: String = STATUS_PENDING,
    val sessionUri: String? = null,
    val bytesUploaded: Long = 0,
    val driveFileId: String? = null,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_PAUSED = "PAUSED"
    }
}
