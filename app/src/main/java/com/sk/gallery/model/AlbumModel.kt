package com.sk.gallery.model

data class AlbumModel(
    val title: String,
    val relativePath: String,
    val coverEntry: FileEntry?,
    val itemCount: Int,
    val isSystemAlbum: Boolean = false,
    val isCreateNew: Boolean = false
)
