package com.sk.gallery.ui.albums

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.sk.gallery.MainActivity
import com.sk.gallery.R
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.FragmentAlbumDetailBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.ui.adapter.MediaAdapter
import com.sk.gallery.ui.viewer.PhotoViewerActivity
import com.sk.gallery.util.applySort
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayList

class AlbumDetailFragment : Fragment() {

    companion object {
        private const val TAG = "AlbumDetailFragment"
        private const val ARG_ALBUM_TITLE = "arg_album_title"
        private const val ARG_ALBUM_PATH = "arg_album_path"

        fun newInstance(title: String, relativePath: String): AlbumDetailFragment {
            return AlbumDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ALBUM_TITLE, title)
                    putString(ARG_ALBUM_PATH, relativePath)
                }
            }
        }
    }

    private var _binding: FragmentAlbumDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: MediaRepository
    private lateinit var adapter: MediaAdapter
    private var albumTitle: String = ""
    private var albumPath: String = ""
    private var albumEntries: List<FileEntry> = emptyList()

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            Toast.makeText(requireContext(), "Deleted selected items", Toast.LENGTH_SHORT).show()
            repository.removeEntriesInstantly(adapter.selectedEntries.toList())
            adapter.clearSelectionMode()
            updateSelectionUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumTitle = arguments?.getString(ARG_ALBUM_TITLE) ?: "Album"
        albumPath = arguments?.getString(ARG_ALBUM_PATH) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = MediaRepository.getInstance(requireContext())

        binding.tvAlbumDetailTitle.text = albumTitle
        binding.btnAlbumBack.setOnClickListener {
            if (!clearSelectionIfActive()) {
                parentFragmentManager.popBackStack()
            }
        }

        adapter = MediaAdapter(
            entries = emptyList(),
            spanCount = 4,
            onItemClick = { entry ->
                val index = albumEntries.indexOf(entry)
                PhotoViewerActivity.currentPhotoList = albumEntries
                val intent = Intent(requireContext(), PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, if (index >= 0) index else 0)
                }
                startActivity(intent)
                requireActivity().overridePendingTransition(R.anim.zoom_in, R.anim.hold)
            },
            onItemLongClick = { _ ->
                updateSelectionUI()
            },
            onSelectionChanged = { _ ->
                updateSelectionUI()
            },
            onFetchCloudClick = {},
            onMarkMissingClick = {}
        )

        binding.rvAlbumDetailGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.rvAlbumDetailGrid.setHasFixedSize(true)
        binding.rvAlbumDetailGrid.setItemViewCacheSize(20)
        binding.rvAlbumDetailGrid.adapter = adapter
        
        com.sk.gallery.util.FastScrollHelper(
            recyclerView = binding.rvAlbumDetailGrid,
            thumbView = binding.fastScrollThumb,
            dateBubble = binding.tvFastScrollDate,
            getDateAtPosition = { pos -> 
                albumEntries.getOrNull(pos)?.let { it.dateModified * 1000L }
            }
        )

        setupSelectionActions()
        setupBackPressedHandler()
        loadAlbumMedia()
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) {
            refreshAlbumEntries(repository.mediaFlow.value)
        }
    }

    private fun setupBackPressedHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (clearSelectionIfActive()) {
                    return
                }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    fun clearSelectionIfActive(): Boolean {
        if (::adapter.isInitialized && adapter.isSelectionMode) {
            adapter.clearSelectionMode()
            updateSelectionUI()
            return true
        }
        return false
    }

    private fun updateSelectionUI() {
        val activity = activity as? MainActivity
        if (adapter.isSelectionMode) {
            binding.selectionTopBar.visibility = View.VISIBLE
            binding.selectionBottomBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "${adapter.selectedEntries.size} selected"
            activity?.setFloatingNavVisibility(false)

            val preferences = AppPreferences(requireContext())
            val allFav = adapter.selectedEntries.isNotEmpty() && adapter.selectedEntries.all { preferences.isFavorite(it.hashId) }
            val favColor = if (allFav) Color.parseColor("#FF5252") else Color.WHITE
            binding.ivActionFavourite.setColorFilter(favColor)
            binding.tvActionFavourite.text = if (allFav) "Favourited" else "Favourite"
            binding.tvActionFavourite.setTextColor(favColor)
        } else {
            binding.selectionTopBar.visibility = View.GONE
            binding.selectionBottomBar.visibility = View.GONE
            activity?.setFloatingNavVisibility(true)
        }
    }

    private fun setupSelectionActions() {
        binding.btnCloseSelection.setOnClickListener {
            adapter.clearSelectionMode()
            updateSelectionUI()
        }

        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
            updateSelectionUI()
        }

        binding.btnActionPrivate.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            val preferences = AppPreferences(requireContext())
            for (entry in selected) {
                preferences.togglePrivate(entry.hashId)
            }
            Toast.makeText(requireContext(), "Moved ${selected.size} items to Private", Toast.LENGTH_SHORT).show()
            adapter.clearSelectionMode()
            updateSelectionUI()
        }

        binding.btnActionFavourite.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            val preferences = AppPreferences(requireContext())
            val allFav = selected.isNotEmpty() && selected.all { preferences.isFavorite(it.hashId) }
            for (entry in selected) {
                if (allFav) {
                    if (preferences.isFavorite(entry.hashId)) {
                        preferences.toggleFavorite(entry.hashId)
                    }
                } else {
                    if (!preferences.isFavorite(entry.hashId)) {
                        preferences.toggleFavorite(entry.hashId)
                    }
                }
            }
            val msg = if (allFav) "Removed from Favourites" else "Added to Favourites"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            adapter.clearSelectionMode()
            updateSelectionUI()
        }

        binding.btnActionMove.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener
            com.sk.gallery.util.MoveHelper.showMoveDialog(requireContext(), selected) {
                adapter.clearSelectionMode()
                updateSelectionUI()
                loadAlbumMedia()
            }
        }

        binding.btnActionDelete.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(requireContext())
                .setTitle("Delete Selected Items")
                .setMessage("Are you sure you want to delete ${selected.size} items?")
                .setPositiveButton("Delete") { _, _ ->
                    deleteMediaEntries(selected)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnActionShare.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            val uris = ArrayList<Uri>()
            for (entry in selected) {
                val file = File(Environment.getExternalStorageDirectory(), entry.relativePath)
                if (file.exists()) {
                    val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
                    uris.add(uri)
                }
            }

            if (uris.isNotEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Selected Media"))
            }
        }
    }

    private fun deleteMediaEntries(entries: List<FileEntry>) {
        viewLifecycleOwner.lifecycleScope.launch {
            com.sk.gallery.data.trash.TrashManager.moveToTrash(requireContext(), entries) {
                Toast.makeText(requireContext(), "Moved ${entries.size} items to Recently Deleted", Toast.LENGTH_SHORT).show()
                adapter.clearSelectionMode()
                updateSelectionUI()
            }
        }
    }

    private fun loadAlbumMedia() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.mediaFlow.collect { allMedia ->
                refreshAlbumEntries(allMedia)
            }
        }
    }

    private fun refreshAlbumEntries(allMedia: List<FileEntry>) {
        if (allMedia.isEmpty() && ::repository.isInitialized) {
            repository.loadInitialMedia()
        }

        albumEntries = when (albumTitle) {
            "Recent" -> repository.getRecentMedia()
            "Camera" -> repository.getCameraMedia()
            "Favourites" -> repository.getFavorites()
            "Screenshots" -> repository.getScreenshots()
            "Videos" -> repository.getVideos()
            "WhatsApp Images" -> allMedia.filter { it.relativePath.contains("WhatsApp", ignoreCase = true) && !it.relativePath.contains("Documents", ignoreCase = true) && !it.mimeType.startsWith("video", ignoreCase = true) }
            "WhatsApp Documents" -> allMedia.filter { it.relativePath.contains("WhatsApp", ignoreCase = true) && it.relativePath.contains("Documents", ignoreCase = true) }
            "WhatsApp Videos" -> allMedia.filter { it.relativePath.contains("WhatsApp", ignoreCase = true) && it.mimeType.startsWith("video", ignoreCase = true) }
            else -> allMedia.filter {
                val albumPathForFile = com.sk.gallery.util.FileUtils.getAlbumRelativePath(it.relativePath)
                albumPathForFile.equals(albumPath, ignoreCase = true)
            }
        }.applySort(AppPreferences(requireContext()))

        if (albumEntries.isEmpty()) {
            binding.rvAlbumDetailGrid.visibility = View.GONE
            binding.tvEmptyAlbum.visibility = View.VISIBLE
        } else {
            binding.rvAlbumDetailGrid.visibility = View.VISIBLE
            binding.tvEmptyAlbum.visibility = View.GONE
            adapter.updateEntries(albumEntries)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
