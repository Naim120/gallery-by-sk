package com.sk.gallery.util

import android.app.Dialog
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.R
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.model.AlbumModel
import com.sk.gallery.model.FileEntry
import com.sk.gallery.ui.adapter.AlbumAdapter
import java.io.File

object MoveHelper {

    fun showLocationPicker(context: Context, onLocationSelected: (File) -> Unit) {
        showDialogInternal(context, emptyList(), isPicker = true, onLocationSelected = onLocationSelected)
    }

    fun showMoveDialog(context: Context, entries: List<FileEntry>, onMoved: () -> Unit) {
        if (entries.isEmpty()) return
        showDialogInternal(context, entries, isPicker = false, onMoved = onMoved)
    }
    
    private fun showDialogInternal(context: Context, entries: List<FileEntry>, isPicker: Boolean, onMoved: (() -> Unit)? = null, onLocationSelected: ((File) -> Unit)? = null) {

        val repo = MediaRepository.getInstance(context)
        val allMedia = repo.mediaFlow.value

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_move_to)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val btnClose = dialog.findViewById<ImageButton>(R.id.btn_close_move)
        btnClose?.setOnClickListener {
            dialog.dismiss()
        }

        val rvAlbums = dialog.findViewById<RecyclerView>(R.id.rv_move_albums)
        rvAlbums?.layoutManager = GridLayoutManager(context, 3)

        val albumList = mutableListOf<AlbumModel>()
        albumList.add(AlbumModel("Create New", "", null, 0, isSystemAlbum = false, isCreateNew = true))

        val cameraList = repo.getCameraMedia()
        val favList = repo.getFavorites()
        val screenshotList = repo.getScreenshots()

        albumList.add(AlbumModel("Camera", "DCIM/Camera", cameraList.firstOrNull(), cameraList.size, true))
        albumList.add(AlbumModel("Favourites", "", favList.firstOrNull(), favList.size, true))
        albumList.add(AlbumModel("Screenshot", "Pictures/Screenshots", screenshotList.firstOrNull(), screenshotList.size, true))

        val folderGroups = allMedia.groupBy { entry ->
            val path = entry.relativePath
            when {
                path.contains("WhatsApp", ignoreCase = true) -> {
                    if (path.contains("Documents", ignoreCase = true)) "WhatsApp Documents"
                    else "WhatsApp Images"
                }
                path.contains("Telegram", ignoreCase = true) -> "Telegram"
                path.contains("Instagram", ignoreCase = true) -> "Instagram"
                path.contains("ChatGPT", ignoreCase = true) -> "ChatGPT"
                path.contains("Pictures/", ignoreCase = true) -> {
                    val sub = path.substringAfter("Pictures/").substringBefore("/")
                    if (sub.isNotBlank()) sub else "Pictures"
                }
                path.contains("DCIM/", ignoreCase = true) -> {
                    val sub = path.substringAfter("DCIM/").substringBefore("/")
                    if (sub.isNotBlank()) sub else "DCIM"
                }
                else -> {
                    val firstDir = path.substringBefore("/")
                    if (firstDir.isNotBlank()) firstDir else "Other"
                }
            }
        }

        for ((folderName, folderEntries) in folderGroups) {
            if (folderName.equals("Camera", true) ||
                folderName.equals("Screenshots", true) ||
                folderName.equals("Screenshot", true) ||
                folderName.equals("Recent", true) ||
                folderName.equals("Favourites", true)) continue
            if (folderEntries.isEmpty()) continue
            val firstEntry = folderEntries.first()
            val parentPath = com.sk.gallery.util.FileUtils.getAlbumRelativePath(firstEntry.relativePath)
            albumList.add(AlbumModel(folderName, parentPath, firstEntry, folderEntries.size))
        }

        val customPaths = AppPreferences(context).getCustomAlbums()
        for (relPath in customPaths) {
            val title = relPath.substringAfterLast("/").ifEmpty { relPath }
            val exists = albumList.any { it.relativePath.equals(relPath, true) || it.title.equals(title, true) }
            if (!exists) {
                val matchingEntries = allMedia.filter { it.relativePath.startsWith(relPath, true) }
                albumList.add(AlbumModel(title, relPath, matchingEntries.firstOrNull(), matchingEntries.size))
            }
        }

        val adapter = AlbumAdapter(albumList, onAlbumClick = { album ->
            dialog.dismiss()
            if (album.isCreateNew) {
                if (isPicker) {
                    showCreateFolderPicker(context, onLocationSelected!!)
                } else {
                    showCreateFolderDialog(context, entries, onMoved!!)
                }
            } else if (album.title == "Favourites") {
                if (!isPicker) {
                    val prefs = AppPreferences(context)
                    for (entry in entries) {
                        if (!prefs.isFavorite(entry.hashId)) {
                            prefs.toggleFavorite(entry.hashId)
                        }
                    }
                    Toast.makeText(context, "Added ${entries.size} item(s) to Favourites", Toast.LENGTH_SHORT).show()
                    onMoved?.invoke()
                }
            } else {
                val targetDir = when (album.title) {
                    "Camera" -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                    "Screenshot", "Screenshots" -> File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots")
                    else -> {
                        if (album.relativePath.isNotEmpty()) {
                            File(Environment.getExternalStorageDirectory(), album.relativePath)
                        } else {
                            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album.title)
                        }
                    }
                }
                if (isPicker) {
                    onLocationSelected?.invoke(targetDir)
                } else {
                    performMove(context, entries, targetDir, onMoved!!)
                }
            }
        })
        rvAlbums?.adapter = adapter
        dialog.show()
    }

    private fun showCreateFolderPicker(context: Context, onLocationSelected: (File) -> Unit) {
        val editText = EditText(context).apply {
            hint = "Folder Name (e.g. Pictures/Vacation)"
            setPadding(48, 36, 48, 36)
        }
        AlertDialog.Builder(context)
            .setTitle("New Folder")
            .setView(editText)
            .setPositiveButton("Select") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val targetDir = if (name.contains("/")) {
                        File(Environment.getExternalStorageDirectory(), name)
                    } else {
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name)
                    }
                    onLocationSelected(targetDir)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showCreateFolderDialog(context: Context, entries: List<FileEntry>, onMoved: () -> Unit) {
        val editText = EditText(context).apply {
            hint = "Folder Name (e.g. Pictures/Vacation)"
            setPadding(48, 36, 48, 36)
        }
        AlertDialog.Builder(context)
            .setTitle("New Folder")
            .setView(editText)
            .setPositiveButton("Create & Move") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val targetDir = if (name.contains("/")) {
                        File(Environment.getExternalStorageDirectory(), name)
                    } else {
                        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), name)
                    }
                    performMove(context, entries, targetDir, onMoved)
                } else {
                    Toast.makeText(context, "Folder name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performMove(context: Context, entries: List<FileEntry>, targetDir: File, onMoved: () -> Unit) {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val repo = MediaRepository.getInstance(context)
        var movedCount = 0
        val movedEntries = mutableListOf<FileEntry>()
        val newPaths = mutableListOf<String>()

        for (entry in entries) {
            val srcFile = File(Environment.getExternalStorageDirectory(), entry.relativePath)
            if (!srcFile.exists()) continue

            val destFile = File(targetDir, entry.fileName)
            if (srcFile.absolutePath == destFile.absolutePath) continue

            var success = false
            try {
                val originalLastModified = srcFile.lastModified()
                if (srcFile.renameTo(destFile)) {
                    destFile.setLastModified(originalLastModified)
                    success = true
                } else {
                    srcFile.copyTo(destFile, overwrite = true)
                    destFile.setLastModified(originalLastModified)
                    if (srcFile.delete()) {
                        success = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (success) {
                movedCount++
                movedEntries.add(entry)
                newPaths.add(destFile.absolutePath)
            }
        }

        if (movedCount > 0) {
            repo.removeEntriesInstantly(movedEntries)
            MediaScannerConnection.scanFile(context, newPaths.toTypedArray(), null) { _, _ ->
                // MediaStore updated
            }
            Toast.makeText(context, "Moved $movedCount item(s) to ${targetDir.name}", Toast.LENGTH_SHORT).show()
            onMoved()
        } else {
            Toast.makeText(context, "Failed to move items", Toast.LENGTH_SHORT).show()
        }
    }
}
