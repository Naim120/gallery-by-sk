package com.sk.gallery.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.sk.gallery.R
import com.sk.gallery.data.PrivateVaultManager
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.FragmentAlbumDetailBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.ui.adapter.TimelineAdapter
import com.sk.gallery.ui.viewer.PhotoViewerActivity

class PrivateAlbumDetailActivity : AppCompatActivity() {

    private lateinit var binding: FragmentAlbumDetailBinding
    private lateinit var adapter: TimelineAdapter
    private var albumTitle: String = ""
    private var albumEntries: List<FileEntry> = emptyList()
    var isLaunchingViewer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding = FragmentAlbumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        albumTitle = intent.getStringExtra("ALBUM_TITLE") ?: "Album"
        binding.tvAlbumDetailTitle.text = albumTitle
        
        binding.btnAlbumBack.setOnClickListener {
            finish()
        }
        
        // Hide unused UI
        binding.selectionTopBar.visibility = View.GONE
        binding.selectionBottomBar.visibility = View.GONE
        binding.btnAlbumMenu.visibility = View.GONE // Remove unused 3-dot menu in Private Safe
        
        // Force Dark Theme appearance for the Private Vault
        binding.root.setBackgroundColor(android.graphics.Color.parseColor("#121212"))
        binding.tvAlbumDetailTitle.setTextColor(android.graphics.Color.WHITE)
        binding.btnAlbumBack.setColorFilter(android.graphics.Color.WHITE)
        binding.btnCloseSelection.setColorFilter(android.graphics.Color.WHITE)
        binding.tvSelectionCount.setTextColor(android.graphics.Color.WHITE)
        binding.btnSelectAll.setTextColor(android.graphics.Color.WHITE)
        binding.selectionTopBar.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        
        window.statusBarColor = android.graphics.Color.parseColor("#121212")
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::adapter.isInitialized && adapter.isSelectionMode) {
                    adapter.clearSelectionMode()
                    binding.selectionTopBar.visibility = View.GONE
                    binding.selectionBottomBar.visibility = View.GONE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val prefs = AppPreferences(this)
        val columns = prefs.getGridColumns()

        adapter = TimelineAdapter(
            items = emptyList(),
            spanCount = columns,
            onItemClick = { _, index ->
                isLaunchingViewer = true
                PhotoViewerActivity.currentPhotoList = albumEntries
                val intent = Intent(this, PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_INITIAL_INDEX, index)
                    putExtra("EXTRA_IS_PRIVATE", true)
                }
                startActivity(intent)
                overridePendingTransition(R.anim.zoom_in, R.anim.hold)
            },
            onItemLongClick = { _ -> },
            onSelectionChanged = { selectedEntries ->
                if (selectedEntries.isNotEmpty()) {
                    binding.selectionTopBar.visibility = View.VISIBLE
                    binding.selectionBottomBar.visibility = View.VISIBLE
                    binding.tvSelectionCount.text = "${selectedEntries.size} selected"
                    
                    binding.btnActionShare.visibility = View.GONE
                    binding.btnActionFavourite.visibility = View.GONE
                    binding.btnActionDelete.visibility = View.GONE
                    
                    val tvPrivate = binding.btnActionPrivate.getChildAt(1) as android.widget.TextView
                    tvPrivate.text = "Set Public"
                    val ivPrivate = binding.btnActionPrivate.getChildAt(0) as android.widget.ImageView
                    ivPrivate.setImageResource(R.drawable.ic_public_modern)
                    
                    val totalMedia = albumEntries.size
                    if (selectedEntries.size == totalMedia && totalMedia > 0) {
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

        binding.btnActionPrivate.setOnClickListener {
            val selected = adapter.selectedEntries.toList()
            if (selected.isEmpty()) return@setOnClickListener

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Set as Public")
                .setMessage("Where do you want to restore these files?")
                .setPositiveButton("Original Location") { _, _ ->
                    val hashIds = selected.map { it.hashId }
                    PrivateVaultManager.restoreFromVault(this, hashIds) {
                        Toast.makeText(this, "Restored ${hashIds.size} items", Toast.LENGTH_SHORT).show()
                        adapter.clearSelectionMode()
                        onResume()
                    }
                }
                .setNeutralButton("Custom Location") { _, _ ->
                    com.sk.gallery.util.MoveHelper.showLocationPicker(this) { targetDir ->
                        val hashIds = selected.map { it.hashId }
                        PrivateVaultManager.restoreFromVault(this, hashIds, targetDir) {
                            Toast.makeText(this, "Restored ${hashIds.size} items", Toast.LENGTH_SHORT).show()
                            adapter.clearSelectionMode()
                            onResume()
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
        
        binding.btnSelectAll.setOnClickListener {
            val totalMedia = albumEntries.size
            if (adapter.selectedEntries.size == totalMedia && totalMedia > 0) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }

        val gridLayoutManager = GridLayoutManager(this, columns)
        gridLayoutManager.spanSizeLookup = adapter.getSpanSizeLookup(columns)
        binding.rvAlbumDetailGrid.layoutManager = gridLayoutManager
        binding.rvAlbumDetailGrid.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        isLaunchingViewer = false
        val allEntries = PrivateVaultManager.getVaultEntries(this)
        albumEntries = if (albumTitle == "Photos") {
            allEntries.filter { it.mimeType.startsWith("image") }
        } else {
            allEntries.filter { it.mimeType.startsWith("video") }
        }
        adapter.updateEntries(albumEntries)
        
        if (albumEntries.isEmpty()) {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isLaunchingViewer && !isFinishing) {
            finishAffinity()
        }
    }
}
