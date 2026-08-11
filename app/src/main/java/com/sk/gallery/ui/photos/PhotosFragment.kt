package com.sk.gallery.ui.photos

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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sk.gallery.MainActivity
import com.sk.gallery.R
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.FragmentPhotosBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.ui.adapter.TimelineAdapter
import com.sk.gallery.ui.viewer.PhotoViewerActivity
import com.sk.gallery.util.PermissionManager
import com.sk.gallery.util.applySort
import kotlinx.coroutines.launch
import java.io.File

class PhotosFragment : Fragment() {

    companion object {
        private const val TAG = "GalleryBySK"
    }

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!

    private lateinit var repository: MediaRepository
    private lateinit var preferences: AppPreferences
    private lateinit var adapter: TimelineAdapter
    private var allEntriesList: List<FileEntry> = emptyList()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                PermissionManager.requestManageStoragePermission(requireContext())
            }
            showPhotosContent()
            repository.loadInitialMedia()
        } else {
            showPermissionDeniedUI()
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            Toast.makeText(requireContext(), "Deleted selected items", Toast.LENGTH_SHORT).show()
            val selected = adapter.selectedEntries.toList()
            repository.removeEntriesInstantly(selected)
            adapter.clearSelectionMode()
            updateSelectionUI()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val backPressedCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            clearSelectionIfActive()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        
        repository = MediaRepository.getInstance(requireContext())
        preferences = AppPreferences(requireContext())

        setupAdapters()
        setupSelectionActions()
        observeMedia()

        binding.btnGrantPermission.setOnClickListener {
            if (!PermissionManager.hasPermissions(requireContext())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    PermissionManager.requestManageStoragePermission(requireContext())
                } else {
                    permissionLauncher.launch(PermissionManager.getRequiredPermissions())
                }
            } else {
                showPhotosContent()
                repository.loadInitialMedia()
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            repository.loadInitialMedia()
            binding.swipeRefresh.isRefreshing = false
        }

        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (PermissionManager.hasPermissions(requireContext())) {
            showPhotosContent()
        } else {
            showPermissionDeniedUI()
        }
    }

    fun clearSelectionIfActive(): Boolean {
        if (::adapter.isInitialized && adapter.isSelectionMode) {
            adapter.clearSelectionMode()
            updateSelectionUI()
            return true
        }
        return false
    }

    private fun checkPermissionAndLoad() {
        if (!PermissionManager.hasPermissions(requireContext())) {
            showPermissionDeniedUI()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                PermissionManager.requestManageStoragePermission(requireContext())
            } else {
                permissionLauncher.launch(PermissionManager.getRequiredPermissions())
            }
        } else {
            showPhotosContent()
            repository.loadInitialMedia()
        }
    }

    private fun showPermissionDeniedUI() {
        binding.layoutPermissionDenied.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.GONE
    }

    private fun showPhotosContent() {
        binding.layoutPermissionDenied.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    private fun setupAdapters() {
        val columnCount = preferences.getGridColumns()
        adapter = TimelineAdapter(
            items = emptyList(),
            spanCount = columnCount,
            onItemClick = { entry, index ->
                PhotoViewerActivity.currentPhotoList = allEntriesList
                val intent = Intent(requireContext(), PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
                }
                startActivity(intent)
                requireActivity().overridePendingTransition(R.anim.zoom_in, R.anim.hold)
            },
            onItemLongClick = { _ ->
                updateSelectionUI()
            },
            onSelectionChanged = { _ ->
                updateSelectionUI()
            }
        )

        val gridLayoutManager = GridLayoutManager(requireContext(), columnCount)
        gridLayoutManager.spanSizeLookup = adapter.getSpanSizeLookup(columnCount)
        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = adapter

        com.sk.gallery.util.FastScrollHelper(
            recyclerView = binding.rvPhotos,
            thumbView = binding.fastScrollThumb,
            dateBubble = binding.tvFastScrollDate,
            getDateAtPosition = { pos -> 
                adapter.getDateAtPosition(pos)
            }
        )
    }

    private fun observeMedia() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.mediaFlow.collect { entries ->
                val prefs = AppPreferences(requireContext())
                allEntriesList = entries.applySort(prefs)
                adapter.updateEntries(allEntriesList)
            }
        }
    }

    private fun setupSelectionActions() {
        binding.btnCloseSelection.setOnClickListener {
            adapter.clearSelectionMode()
            updateSelectionUI()
        }

        binding.btnSelectAll.setOnClickListener {
            val totalSelectable = allEntriesList.count { !it.isMissingLocally }
            val isAllSelected = totalSelectable > 0 && adapter.selectedEntries.size == totalSelectable
            if (isAllSelected) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
            updateSelectionUI()
        }

        binding.btnActionPrivate.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener
            
            com.sk.gallery.data.PrivateVaultManager.moveToVault(requireContext(), selected) {
                repository.removeEntriesInstantly(selected)
                Toast.makeText(requireContext(), "Moved ${selected.size} items to Private Safe", Toast.LENGTH_SHORT).show()
                adapter.clearSelectionMode()
                updateSelectionUI()
            }
        }

        binding.btnActionFavourite.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
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
                repository.removeEntriesInstantly(entries)
                Toast.makeText(requireContext(), "Moved ${entries.size} items to Recently Deleted", Toast.LENGTH_SHORT).show()
                adapter.clearSelectionMode()
                updateSelectionUI()
            }
        }
    }

    private fun updateSelectionUI() {
        val activity = activity as? MainActivity
        
        // Update hardware back button handler
        backPressedCallback.isEnabled = adapter.isSelectionMode
        
        if (adapter.isSelectionMode) {
            binding.selectionTopBar.visibility = View.VISIBLE
            binding.selectionBottomBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "${adapter.selectedEntries.size} selected"
            activity?.setFloatingNavVisibility(false)
            
            val totalSelectable = allEntriesList.count { !it.isMissingLocally }
            val isAllSelected = totalSelectable > 0 && adapter.selectedEntries.size == totalSelectable
            binding.btnSelectAll.text = if (isAllSelected) "Deselect All" else "Select All"

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



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
