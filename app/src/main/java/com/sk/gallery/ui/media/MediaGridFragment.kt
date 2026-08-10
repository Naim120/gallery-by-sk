package com.sk.gallery.ui.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.sk.gallery.auth.GoogleSignInManager
import com.sk.gallery.data.MediaStoreScanner
import com.sk.gallery.databinding.FragmentMediaGridBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.restore.RestorationManager
import com.sk.gallery.ui.adapter.MediaAdapter
import kotlinx.coroutines.launch

class MediaGridFragment : Fragment() {

    companion object {
        private const val ARG_RELATIVE_PATH = "arg_relative_path"

        fun newInstance(relativePath: String): MediaGridFragment {
            val fragment = MediaGridFragment()
            val args = Bundle().apply {
                putString(ARG_RELATIVE_PATH, relativePath)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!

    private var folderRelativePath: String = "Pictures/"
    private lateinit var scanner: MediaStoreScanner
    private lateinit var adapter: MediaAdapter
    private var folderEntries: List<FileEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderRelativePath = arguments?.getString(ARG_RELATIVE_PATH) ?: "Pictures/"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scanner = MediaStoreScanner(requireContext())
        binding.tvFolderTitle.text = folderRelativePath

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupRecyclerView()
        loadFolderEntries()
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            entries = emptyList(),
            onItemClick = { entry -> openPhotoViewer(entry) },
            onFetchCloudClick = { entry -> fetchFromCloud(entry) },
            onMarkMissingClick = { entry -> markAsMissing(entry) }
        )
        binding.rvMedia.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvMedia.setHasFixedSize(true)
        binding.rvMedia.setItemViewCacheSize(20)
        binding.rvMedia.adapter = adapter
        
        com.sk.gallery.util.FastScrollHelper(
            recyclerView = binding.rvMedia,
            thumbView = binding.fastScrollThumb,
            dateBubble = binding.tvFastScrollDate,
            getDateAtPosition = { pos -> 
                folderEntries.getOrNull(pos)?.let { it.dateModified * 1000L }
            }
        )
    }

    private fun loadFolderEntries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val manifest = scanner.loadLocalManifest() ?: scanner.scanMediaStore()
            folderEntries = manifest.entries.values.filter { entry ->
                entry.relativePath.startsWith(folderRelativePath)
            }.sortedBy { it.fileName }

            adapter.updateEntries(folderEntries)
        }
    }

    private fun openPhotoViewer(entry: FileEntry) {
        val dialog = PhotoViewerDialogFragment.newInstance(entry)
        dialog.show(childFragmentManager, "photo_viewer")
    }

    private fun fetchFromCloud(entry: FileEntry) {
        val context = requireContext()
        val account = GoogleSignInManager.getLastSignedInAccount(context)
        if (account == null) {
            Toast.makeText(context, "Please sign in to Google Drive first", Toast.LENGTH_SHORT).show()
            loadFolderEntries()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val driveService = GoogleSignInManager.getDriveService(context, account)
                val manager = RestorationManager(context, driveService)
                val success = manager.restoreFile(entry)

                if (success) {
                    Toast.makeText(context, "Restored ${entry.fileName} to $folderRelativePath", Toast.LENGTH_SHORT).show()
                    // Rescan and refresh UI
                    scanner.scanMediaStore()
                    loadFolderEntries()
                } else {
                    Toast.makeText(context, "Failed to restore ${entry.fileName}", Toast.LENGTH_SHORT).show()
                    loadFolderEntries()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                loadFolderEntries()
            }
        }
    }

    private fun markAsMissing(entry: FileEntry) {
        Toast.makeText(requireContext(), "Marked ${entry.fileName} as missing", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
