package com.sk.gallery.ui.folder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.sk.gallery.R
import com.sk.gallery.data.MediaStoreScanner
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.FragmentFolderListBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.model.FolderNode
import com.sk.gallery.model.HierarchyIndex
import com.sk.gallery.ui.adapter.FolderAdapter
import com.sk.gallery.ui.adapter.TimelineAdapter
import com.sk.gallery.ui.media.MediaGridFragment
import com.sk.gallery.ui.media.PhotoViewerDialogFragment
import com.sk.gallery.util.applySort
import kotlinx.coroutines.launch

class FolderListFragment : Fragment() {

    companion object {
        private const val TAG = "GalleryBySK"
        private var cachedManifest: HierarchyIndex? = null
    }

    private var _binding: FragmentFolderListBinding? = null
    private val binding get() = _binding!!

    private lateinit var scanner: MediaStoreScanner
    private lateinit var folderAdapter: FolderAdapter
    private lateinit var timelineAdapter: TimelineAdapter
    private var timelineEntries: List<FileEntry> = emptyList()

    private var isDateViewSelected = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "FolderListFragment: Storage permissions granted by user.")
            loadMediaData(forceRescan = true)
        } else {
            Log.w(TAG, "FolderListFragment: Storage permissions denied by user.")
            Toast.makeText(requireContext(), "Storage permission is required to view media", Toast.LENGTH_LONG).show()
            showEmptyState()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFolderListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scanner = MediaStoreScanner(requireContext())

        setupAdapters()
        setupToggleGroup()

        binding.swipeRefresh.setOnRefreshListener {
            loadMediaData(forceRescan = true)
        }

        checkPermissionsAndLoad()
    }

    private fun setupAdapters() {
        // Folder Adapter
        folderAdapter = FolderAdapter(emptyList()) { folder ->
            openFolder(folder)
        }
        binding.rvFolders.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvFolders.adapter = folderAdapter

        // Timeline Adapter
        timelineAdapter = TimelineAdapter(
            items = emptyList(),
            onItemClick = { entry, _ -> openPhotoViewer(entry) },
            onItemLongClick = { _ -> },
            onSelectionChanged = { _ -> }
        )
        val gridLayoutManager = GridLayoutManager(requireContext(), 3)
        gridLayoutManager.spanSizeLookup = timelineAdapter.getSpanSizeLookup(3)
        binding.rvTimeline.layoutManager = gridLayoutManager
        binding.rvTimeline.adapter = timelineAdapter
        
        com.sk.gallery.util.FastScrollHelper(
            recyclerView = binding.rvTimeline,
            thumbView = binding.fastScrollThumb,
            dateBubble = binding.tvFastScrollDate,
            getDateAtPosition = { pos -> 
                timelineEntries.getOrNull(pos)?.let { it.dateModified * 1000L }
            }
        )
    }

    private fun setupToggleGroup() {
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isDateViewSelected = checkedId == R.id.btn_view_date
                renderCurrentViewMode()
            }
        }
    }

    private fun checkPermissionsAndLoad() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            loadMediaData(forceRescan = false)
        }
    }

    private fun loadMediaData(forceRescan: Boolean) {
        if (!forceRescan && cachedManifest != null) {
            Log.d(TAG, "FolderListFragment: Using in-memory cached manifest.")
            renderCurrentViewMode()
            return
        }

        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val manifest = if (!forceRescan) {
                    scanner.loadLocalManifest() ?: scanner.scanMediaStore()
                } else {
                    scanner.scanMediaStore()
                }

                cachedManifest = manifest
                renderCurrentViewMode()
            } catch (e: Exception) {
                Log.e(TAG, "FolderListFragment: Error loading media", e)
                showEmptyState()
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderCurrentViewMode() {
        val manifest = cachedManifest ?: return
        timelineEntries = manifest.entries.values.toList().applySort(AppPreferences(requireContext()))

        if (timelineEntries.isEmpty()) {
            showEmptyState()
            return
        }

        binding.emptyState.visibility = View.GONE

        if (isDateViewSelected) {
            binding.rvTimeline.visibility = View.VISIBLE
            binding.rvFolders.visibility = View.GONE
            binding.fastScrollThumb.visibility = View.INVISIBLE
            timelineAdapter.updateEntries(timelineEntries)
        } else {
            binding.rvTimeline.visibility = View.GONE
            binding.rvFolders.visibility = View.VISIBLE
            folderAdapter.updateFolders(manifest.folderTree)
        }
    }

    private fun showEmptyState() {
        binding.emptyState.visibility = View.VISIBLE
        binding.rvTimeline.visibility = View.GONE
        binding.rvFolders.visibility = View.GONE
    }

    private fun openFolder(folder: FolderNode) {
        val fragment = MediaGridFragment.newInstance(folder.relativePath)
        parentFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openPhotoViewer(entry: FileEntry) {
        val dialog = PhotoViewerDialogFragment.newInstance(entry)
        dialog.show(childFragmentManager, "photo_viewer")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
