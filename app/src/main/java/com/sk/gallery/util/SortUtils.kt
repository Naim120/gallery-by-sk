package com.sk.gallery.util

import com.sk.gallery.model.FileEntry
import com.sk.gallery.data.local.AppPreferences

fun List<FileEntry>.applySort(preferences: AppPreferences): List<FileEntry> {
    return when (preferences.getSortBy()) {
        "date_asc" -> sortedBy { it.dateModified }
        "name_asc" -> sortedBy { it.fileName.lowercase() }
        else -> sortedByDescending { it.dateModified }
    }
}
