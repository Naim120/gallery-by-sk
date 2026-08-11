package com.sk.gallery.ui.albums

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.sk.gallery.R
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.databinding.FragmentAlbumsBinding
import com.sk.gallery.model.AlbumModel
import com.sk.gallery.model.FileEntry
import com.sk.gallery.ui.adapter.AlbumAdapter
import kotlinx.coroutines.launch
import java.io.File

class AlbumsFragment : Fragment() {

    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: MediaRepository
    private lateinit var adapterAll: AlbumAdapter
    private lateinit var adapterApps: AlbumAdapter
    private lateinit var adapterMore: AlbumAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = MediaRepository.getInstance(requireContext())

        setupAdapters()
        observeAlbums()
        
        binding.btnTrashBin.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.sk.gallery.data.trash.TrashActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) {
            refreshAlbumsUI(repository.mediaFlow.value)
        }
    }

    private fun setupAdapters() {
        val clickListener: (AlbumModel) -> Unit = { album ->
            if (album.isCreateNew) {
                showCreateAlbumDialog()
            } else {
                val fragment = AlbumDetailFragment.newInstance(album.title, album.relativePath)
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_in_right,
                        R.anim.slide_out_left,
                        R.anim.slide_in_left,
                        R.anim.slide_out_right
                    )
                    .add(android.R.id.content, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }

        val longClickListener: (AlbumModel) -> Unit = { album ->
            showAlbumOptionsDialog(album)
        }

        adapterAll = AlbumAdapter(emptyList(), clickListener, longClickListener)
        adapterApps = AlbumAdapter(emptyList(), clickListener, longClickListener)
        adapterMore = AlbumAdapter(emptyList(), clickListener, longClickListener)

        binding.rvAlbumsAll.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvAlbumsAll.adapter = adapterAll

        binding.rvAlbumsApps.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvAlbumsApps.adapter = adapterApps

        binding.rvAlbumsMore.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvAlbumsMore.adapter = adapterMore
    }

    private fun observeAlbums() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Delay slightly to prevent the heavy grouping logic from lagging the ViewPager swipe animation
            kotlinx.coroutines.delay(150)
            
            repository.mediaFlow.collect { allMedia ->
                refreshAlbumsUI(allMedia)
            }
        }
    }

    private fun refreshAlbumsUI(allMedia: List<FileEntry>) {
        if (allMedia.isEmpty()) return

        val context = context ?: return

        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val recentList = repository.getRecentMedia()
            val cameraList = repository.getCameraMedia()
            val favList = repository.getFavorites()
            val screenshotList = repository.getScreenshots()
            val videosList = repository.getVideos()

            val systemAlbums = mutableListOf<AlbumModel>()
            systemAlbums.add(AlbumModel("Create Album", "", null, 0, isSystemAlbum = false, isCreateNew = true))
            systemAlbums.add(AlbumModel("Recent", "", recentList.firstOrNull(), recentList.size, true))
            systemAlbums.add(AlbumModel("Camera", "DCIM/Camera", cameraList.firstOrNull(), cameraList.size, true))
            systemAlbums.add(AlbumModel("Favourites", "", favList.firstOrNull(), favList.size, true))
            systemAlbums.add(AlbumModel("Screenshots", "Pictures/Screenshots", screenshotList.firstOrNull(), screenshotList.size, true))
            systemAlbums.add(AlbumModel("Videos", "", videosList.firstOrNull(), videosList.size, true))

            // Categorize folder groups
            val folderGroups = allMedia.groupBy { entry ->
                val path = entry.relativePath
                when {
                    path.contains("WhatsApp", ignoreCase = true) -> {
                        if (path.contains("Documents", ignoreCase = true)) {
                            "WhatsApp Documents"
                        } else if (entry.mimeType.startsWith("video", ignoreCase = true)) {
                            "WhatsApp Videos"
                        } else {
                            "WhatsApp Images"
                        }
                    }
                    path.contains("Telegram", ignoreCase = true) -> "Telegram"
                    path.contains("Instagram", ignoreCase = true) -> "Instagram"
                    path.contains("ChatGPT", ignoreCase = true) -> "ChatGPT"
                    else -> {
                        val albumPath = com.sk.gallery.util.FileUtils.getAlbumRelativePath(path)
                        val name = com.sk.gallery.util.FileUtils.extractFolderName(albumPath)
                        if (name.isNotBlank()) name else "Other"
                    }
                }
            }

            val appAlbums = mutableListOf<AlbumModel>()
            val moreAlbums = mutableListOf<AlbumModel>()
            
            val prefs = com.sk.gallery.data.local.AppPreferences(context)
            val aliases = prefs.getAlbumAliases()

            for ((folderName, folderEntries) in folderGroups) {
                if (folderEntries.isEmpty()) continue
                val firstEntry = folderEntries.first()
                val parentPath = com.sk.gallery.util.FileUtils.getAlbumRelativePath(firstEntry.relativePath)
                val displayTitle = aliases[parentPath] ?: folderName

                val model = AlbumModel(
                    title = displayTitle,
                    relativePath = parentPath,
                    coverEntry = firstEntry,
                    itemCount = folderEntries.size
                )

                if (folderName.contains("WhatsApp", true) ||
                    folderName.contains("ChatGPT", true) ||
                    folderName.contains("Telegram", true) ||
                    folderName.contains("Instagram", true)) {
                    appAlbums.add(model)
                } else if (folderName != "Camera" && folderName != "Screenshots") {
                    moreAlbums.add(model)
                }
            }

            val customPaths = prefs.getCustomAlbums()
            for (relPath in customPaths) {
                val baseTitle = relPath.substringAfterLast("/").ifEmpty { relPath }
                val title = aliases[relPath] ?: baseTitle
                val existsInMore = moreAlbums.any { it.relativePath.equals(relPath, true) || it.title.equals(title, true) }
                val existsInApps = appAlbums.any { it.relativePath.equals(relPath, true) || it.title.equals(title, true) }
                val existsInSys = systemAlbums.any { it.relativePath.equals(relPath, true) || it.title.equals(title, true) }
                if (!existsInMore && !existsInApps && !existsInSys) {
                    val entries = allMedia.filter { it.relativePath.startsWith(relPath, true) }
                    moreAlbums.add(AlbumModel(title, relPath, entries.firstOrNull(), entries.size))
                }
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (_binding == null) return@withContext
                
                adapterAll.updateAlbums(systemAlbums)

                if (appAlbums.isEmpty()) {
                    binding.tvHeaderApps.visibility = View.GONE
                    binding.rvAlbumsApps.visibility = View.GONE
                } else {
                    binding.tvHeaderApps.visibility = View.VISIBLE
                    binding.rvAlbumsApps.visibility = View.VISIBLE
                    adapterApps.updateAlbums(appAlbums)
                }

                if (moreAlbums.isEmpty()) {
                    binding.tvHeaderMore.visibility = View.GONE
                    binding.rvAlbumsMore.visibility = View.GONE
                } else {
                    binding.tvHeaderMore.visibility = View.VISIBLE
                    binding.rvAlbumsMore.visibility = View.VISIBLE
                    adapterMore.updateAlbums(moreAlbums)
                }
            }
        }
    }

    private fun showCreateAlbumDialog() {
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Album Name (e.g. Vacation 2026)"
            setPadding(48, 36, 48, 36)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("New Album")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    val folder = if (name.contains("/")) {
                        java.io.File(android.os.Environment.getExternalStorageDirectory(), name)
                    } else {
                        java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), name)
                    }
                    if (!folder.exists()) {
                        folder.mkdirs()
                    }
                    val extPath = android.os.Environment.getExternalStorageDirectory().absolutePath
                    val relPath = if (folder.absolutePath.startsWith(extPath)) {
                        folder.absolutePath.substring(extPath.length).trimStart('/')
                    } else {
                        "Pictures/" + folder.name
                    }
                    com.sk.gallery.data.local.AppPreferences(requireContext()).addCustomAlbum(relPath)
                    android.widget.Toast.makeText(requireContext(), "Album '${folder.name}' created!", android.widget.Toast.LENGTH_SHORT).show()
                    refreshAlbumsUI(repository.mediaFlow.value)
                } else {
                    android.widget.Toast.makeText(requireContext(), "Album name cannot be empty", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAlbumOptionsDialog(album: AlbumModel) {
        val options = arrayOf("Rename", "Delete")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(album.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(album)
                    1 -> showDeleteConfirmation(album)
                }
            }
            .show()
    }

    private fun showRenameDialog(album: AlbumModel) {
        val editText = android.widget.EditText(requireContext()).apply {
            setText(album.title)
            setPadding(48, 36, 48, 36)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Rename Album")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    com.sk.gallery.data.local.AppPreferences(requireContext())
                        .setAlbumAlias(album.relativePath, newName)
                    refreshAlbumsUI(repository.mediaFlow.value)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(album: AlbumModel) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Album")
            .setMessage("Are you sure you want to delete '${album.title}'?\nThis will permanently delete the folder and ALL media items inside it.")
            .setPositiveButton("Delete", null) // Set null here and override later to prevent auto-dismiss if error
            .setNegativeButton("Cancel", null)
            .create().apply {
                setOnShowListener {
                    getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val folder = File(android.os.Environment.getExternalStorageDirectory(), album.relativePath)
                        
                        // 1. Delete all matching database entries from MediaStore first to release any system locks
                        try {
                            val contentResolver = requireContext().contentResolver
                            val where = "${android.provider.MediaStore.MediaColumns.DATA} LIKE ?"
                            val selectionArgs = arrayOf("${folder.absolutePath}/%")
                            contentResolver.delete(android.provider.MediaStore.Files.getContentUri("external"), where, selectionArgs)
                            
                            val folderWhere = "${android.provider.MediaStore.MediaColumns.DATA} = ?"
                            val folderSelectionArgs = arrayOf(folder.absolutePath)
                            contentResolver.delete(android.provider.MediaStore.Files.getContentUri("external"), folderWhere, folderSelectionArgs)
                        } catch (e: Exception) {
                            android.util.Log.w("GalleryBySK", "Failed to clear MediaStore entries for album", e)
                        }

                        // 2. Now physically delete the folder and its contents recursively
                        val deleted = if (folder.exists()) {
                            folder.deleteRecursively()
                        } else {
                            true
                        }

                        // Also clean up empty parent directories up to external storage root
                        if (deleted) {
                            var currentDir = folder.parentFile
                            val rootDir = android.os.Environment.getExternalStorageDirectory()
                            while (currentDir != null && currentDir.absolutePath != rootDir.absolutePath) {
                                if (currentDir.exists() && currentDir.isDirectory && currentDir.list()?.isEmpty() == true) {
                                    currentDir.delete()
                                    currentDir = currentDir.parentFile
                                } else {
                                    break
                                }
                            }
                        }

                        if (deleted) {
                            // 3. Scan the deleted folder path and parent path so MediaStore realizes it is completely removed
                            android.media.MediaScannerConnection.scanFile(
                                requireContext(),
                                arrayOf(folder.absolutePath),
                                null
                            ) { _, _ -> }
                            folder.parentFile?.let { parent ->
                                android.media.MediaScannerConnection.scanFile(
                                    requireContext(),
                                    arrayOf(parent.absolutePath),
                                    null
                                ) { _, _ -> }
                            }
                            
                            com.sk.gallery.data.local.AppPreferences(requireContext())
                                .removeCustomAlbum(album.relativePath)
                                
                            val filesToRemove = repository.mediaFlow.value.filter { it.relativePath.startsWith(album.relativePath) }
                            repository.removeEntriesInstantly(filesToRemove)
                            
                            android.widget.Toast.makeText(requireContext(), "Album deleted", android.widget.Toast.LENGTH_SHORT).show()
                            dismiss()
                        } else {
                            android.widget.Toast.makeText(requireContext(), "Failed to delete album. Please check storage permissions.", android.widget.Toast.LENGTH_LONG).show()
                            dismiss()
                        }
                    }
                }
                show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
