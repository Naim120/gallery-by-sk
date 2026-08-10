package com.sk.gallery.data.trash

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trash_entries")
data class TrashEntry(
    @PrimaryKey
    val originalHashId: String,
    val originalPath: String,
    val trashFileName: String, // Just the file name inside the Trash directory
    val originalFileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val deletedAt: Long // Unix timestamp when it was trashed
)
