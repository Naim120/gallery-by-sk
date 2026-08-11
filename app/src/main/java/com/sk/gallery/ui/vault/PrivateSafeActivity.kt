package com.sk.gallery.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.sk.gallery.R
import com.sk.gallery.data.PrivateVaultManager
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.ActivityPrivateSafeBinding
import com.sk.gallery.databinding.FragmentPhotosBinding
import com.sk.gallery.databinding.FragmentAlbumsBinding
import com.sk.gallery.model.AlbumModel
import com.sk.gallery.model.FileEntry
import com.sk.gallery.ui.adapter.AlbumAdapter
import com.sk.gallery.ui.adapter.TimelineAdapter
import com.sk.gallery.ui.viewer.PhotoViewerActivity

class PrivateSafeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivateSafeBinding
    var isLaunchingViewer = false
    
    companion object {
        var vaultEntries: List<FileEntry> = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = android.graphics.Color.parseColor("#121212")
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        
        binding = ActivityPrivateSafeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCloseVault.setOnClickListener {
            finish()
        }

        binding.btnVaultSettings.setOnClickListener {
            isLaunchingViewer = true
            val intent = Intent(this, VaultSettingsActivity::class.java)
            startActivity(intent)
        }

        binding.viewPagerVault.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) VaultTimelineFragment() else VaultAlbumsFragment()
            }
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPagerVault) { tab, position ->
            tab.text = if (position == 0) "Photos" else "Albums"
        }.attach()
    }

    private val syncReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.sk.gallery.VAULT_SYNC_COMPLETED") {
                // Reload entries and refresh UI live
                vaultEntries = PrivateVaultManager.getVaultEntries(this@PrivateSafeActivity)
                supportFragmentManager.fragments.forEach { fragment ->
                    if (fragment is VaultRefreshable) {
                        fragment.refreshVaultData()
                    }
                    // ViewPager2 fragments might be nested or we just need to iterate child fragments if any, 
                    // but actually ViewPager2 adds them directly to supportFragmentManager.
                    // Just in case, also try to refresh by notifying the adapter if needed.
                    fragment.childFragmentManager.fragments.forEach { child ->
                        if (child is VaultRefreshable) {
                            child.refreshVaultData()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isLaunchingViewer = false
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(syncReceiver, android.content.IntentFilter("com.sk.gallery.VAULT_SYNC_COMPLETED"))
            
        // Reload entries on resume in case we returned from Viewer and Set Public
        vaultEntries = PrivateVaultManager.getVaultEntries(this)
        
        // Notify fragments to refresh
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is VaultRefreshable) {
                fragment.refreshVaultData()
            }
            fragment.childFragmentManager.fragments.forEach { child ->
                if (child is VaultRefreshable) {
                    child.refreshVaultData()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(syncReceiver)
            
        if (!isChangingConfigurations && !isLaunchingViewer && !isFinishing) {
            finishAffinity()
        }
    }

    override fun onStop() {
        super.onStop()
    }
}

interface VaultRefreshable {
    fun refreshVaultData()
}

class VaultTimelineFragment : Fragment(), VaultRefreshable {
    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: TimelineAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    private val backPressedCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::adapter.isInitialized && adapter.isSelectionMode) {
                adapter.clearSelectionMode()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        
        // Hide unused main gallery UI elements
        binding.layoutPermissionDenied.visibility = View.GONE
        binding.swipeRefresh.isEnabled = false
        binding.selectionTopBar.visibility = View.GONE
        binding.selectionBottomBar.visibility = View.GONE
        binding.fastScrollThumb.visibility = View.GONE
        binding.tvFastScrollDate.visibility = View.GONE

        val prefs = AppPreferences(requireContext())
        val columns = prefs.getGridColumns()

        adapter = TimelineAdapter(
            items = emptyList(),
            spanCount = columns,
            onItemClick = { _, index ->
                (activity as? PrivateSafeActivity)?.isLaunchingViewer = true
                PhotoViewerActivity.currentPhotoList = PrivateSafeActivity.vaultEntries
                val intent = Intent(requireContext(), PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
                    putExtra("EXTRA_IS_PRIVATE", true)
                }
                startActivity(intent)
                requireActivity().overridePendingTransition(R.anim.zoom_in, R.anim.hold)
            },
            onItemLongClick = { _ -> },
            onSelectionChanged = { selectedEntries ->
                backPressedCallback.isEnabled = adapter.isSelectionMode
                if (selectedEntries.isNotEmpty()) {
                    binding.selectionTopBar.visibility = View.VISIBLE
                    binding.selectionBottomBar.visibility = View.VISIBLE
                    binding.tvSelectionCount.text = "${selectedEntries.size} selected"
                    
                    binding.btnActionShare.visibility = View.GONE
                    binding.btnActionFavourite.visibility = View.GONE
                    binding.btnActionDelete.visibility = View.VISIBLE
                    
                    val tvPrivate = binding.btnActionPrivate.getChildAt(1) as android.widget.TextView
                    tvPrivate.text = "Set Public"
                    val ivPrivate = binding.btnActionPrivate.getChildAt(0) as android.widget.ImageView
                    ivPrivate.setImageResource(R.drawable.ic_public_modern)
                    if (selectedEntries.size == adapter.items.filterIsInstance<com.sk.gallery.ui.adapter.TimelineItem.Media>().size) {
                        binding.btnSelectAll.text = "Deselect All"
                    } else {
                        binding.btnSelectAll.text = "Select All"
                    }
                } else {
                    binding.selectionTopBar.visibility = View.GONE
                    binding.selectionBottomBar.visibility = View.GONE
                    binding.btnSelectAll.text = "Select All"
                }
            }
        )
        
        binding.btnSelectAll.setOnClickListener {
            val totalMedia = adapter.items.filterIsInstance<com.sk.gallery.ui.adapter.TimelineItem.Media>().size
            if (adapter.selectedEntries.size == totalMedia && totalMedia > 0) {
                adapter.clearSelectionMode()
            } else {
                adapter.selectAll()
            }
        }

        binding.btnActionDelete.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Permanently Delete?")
                .setMessage("Are you sure you want to permanently delete these ${selected.size} files? This action cannot be undone and the files will not be moved to Recently Deleted.")
                .setPositiveButton("Delete") { _, _ ->
                    var deletedCount = 0
                    selected.forEach { entry ->
                        if (com.sk.gallery.data.PrivateVaultManager.deleteFromVault(requireContext(), entry.hashId)) {
                            deletedCount++
                        }
                    }
                    android.widget.Toast.makeText(requireContext(), "Permanently deleted $deletedCount items", android.widget.Toast.LENGTH_SHORT).show()
                    adapter.clearSelectionMode()
                    (activity as? PrivateSafeActivity)?.let {
                        PrivateSafeActivity.vaultEntries = com.sk.gallery.data.PrivateVaultManager.getVaultEntries(it)
                        it.supportFragmentManager.fragments.forEach { f -> (f as? VaultRefreshable)?.refreshVaultData() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnActionPrivate.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Set as Public")
                .setMessage("Where do you want to restore these files?")
                .setPositiveButton("Original Location") { _, _ ->
                    val hashIds = selected.map { it.hashId }
                    com.sk.gallery.data.PrivateVaultManager.restoreFromVault(requireContext(), hashIds) {
                        android.widget.Toast.makeText(requireContext(), "Restored ${hashIds.size} items", android.widget.Toast.LENGTH_SHORT).show()
                        adapter.clearSelectionMode()
                        (activity as? PrivateSafeActivity)?.let {
                            PrivateSafeActivity.vaultEntries = com.sk.gallery.data.PrivateVaultManager.getVaultEntries(it)
                            it.supportFragmentManager.fragments.forEach { f -> (f as? VaultRefreshable)?.refreshVaultData() }
                        }
                    }
                }
                .setNeutralButton("Custom Location") { _, _ ->
                    com.sk.gallery.util.MoveHelper.showLocationPicker(requireContext()) { targetDir ->
                        val hashIds = selected.map { it.hashId }
                        com.sk.gallery.data.PrivateVaultManager.restoreFromVault(requireContext(), hashIds, targetDir) {
                            android.widget.Toast.makeText(requireContext(), "Restored ${hashIds.size} items", android.widget.Toast.LENGTH_SHORT).show()
                            adapter.clearSelectionMode()
                            (activity as? PrivateSafeActivity)?.let {
                                PrivateSafeActivity.vaultEntries = com.sk.gallery.data.PrivateVaultManager.getVaultEntries(it)
                                it.supportFragmentManager.fragments.forEach { f -> (f as? VaultRefreshable)?.refreshVaultData() }
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnCloseSelection.setOnClickListener {
            adapter.clearSelectionMode()
            binding.selectionTopBar.visibility = View.GONE
            binding.selectionBottomBar.visibility = View.GONE
        }

        val gridLayoutManager = GridLayoutManager(requireContext(), columns)
        gridLayoutManager.spanSizeLookup = adapter.getSpanSizeLookup(columns)
        binding.rvPhotos.layoutManager = gridLayoutManager
        binding.rvPhotos.adapter = adapter
        
        refreshVaultData()
    }

    override fun refreshVaultData() {
        if (_binding != null) {
            adapter.updateEntries(PrivateSafeActivity.vaultEntries)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class VaultAlbumsFragment : Fragment(), VaultRefreshable {
    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapterAll: AlbumAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Hide unused albums UI
        binding.tvHeaderApps.visibility = View.GONE
        binding.rvAlbumsApps.visibility = View.GONE
        binding.tvHeaderMore.visibility = View.GONE
        binding.rvAlbumsMore.visibility = View.GONE
        binding.btnTrashBin.visibility = View.GONE
        
        adapterAll = AlbumAdapter(
            albums = emptyList(),
            onAlbumClick = { album ->
                val filtered = if (album.title == "Photos") {
                    PrivateSafeActivity.vaultEntries.filter { it.mimeType.startsWith("image") }
                } else {
                    PrivateSafeActivity.vaultEntries.filter { it.mimeType.startsWith("video") }
                }
                if (filtered.isNotEmpty()) {
                    (activity as? PrivateSafeActivity)?.isLaunchingViewer = true
                    val intent = Intent(requireContext(), PrivateAlbumDetailActivity::class.java).apply {
                        putExtra("ALBUM_TITLE", album.title)
                    }
                    startActivity(intent)
                }
            },
            onAlbumLongClick = { _ -> }
        )
        
        binding.rvAlbumsAll.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvAlbumsAll.adapter = adapterAll
        
        refreshVaultData()
    }

    override fun refreshVaultData() {
        if (_binding != null) {
            val photos = PrivateSafeActivity.vaultEntries.filter { it.mimeType.startsWith("image") }
            val videos = PrivateSafeActivity.vaultEntries.filter { it.mimeType.startsWith("video") }
            
            val albums = listOf(
                AlbumModel("Photos", "", photos.firstOrNull(), photos.size, true),
                AlbumModel("Videos", "", videos.firstOrNull(), videos.size, true)
            )
            adapterAll.updateAlbums(albums)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
