package com.sk.gallery.ui.picker

import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.sk.gallery.R
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.databinding.ActivityMediaPickerBinding
import com.sk.gallery.model.FileEntry
import com.sk.gallery.util.FastScrollHelper
import com.sk.gallery.util.FileUtils
import com.sk.gallery.util.PermissionManager
import kotlinx.coroutines.launch
import java.io.File

class MediaPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaPickerBinding
    private lateinit var repository: MediaRepository
    private lateinit var mediaAdapter: MediaPickerAdapter
    private lateinit var chipAdapter: FilterChipAdapter

    private var allowMultiple: Boolean = false
    private var allFilteredEntries: List<FileEntry> = emptyList()
    private var currentDisplayedEntries: List<FileEntry> = emptyList()
    private var selectedChipId: String = "all"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            showMediaContent()
            repository.loadInitialMedia()
        } else {
            showPermissionDeniedUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MediaRepository.getInstance(this)
        allowMultiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

        setupHeader()
        setupAdapters()
        setupListeners()
        setupBackPressed()

        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (PermissionManager.hasPermissions(this)) {
            showMediaContent()
        } else {
            showPermissionDeniedUI()
        }
    }

    private fun setupHeader() {
        val baseType = intent.type.orEmpty()
        val extraMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        val isVideoOnly = (baseType.startsWith("video/", true) && extraMimes.isNullOrEmpty()) ||
                (extraMimes?.all { it.startsWith("video/", true) } == true)
        val isImageOnly = (baseType.startsWith("image/", true) && extraMimes.isNullOrEmpty()) ||
                (extraMimes?.all { it.startsWith("image/", true) } == true)

        binding.tvTitle.text = when {
            isVideoOnly -> if (allowMultiple) "Select videos" else "Select video"
            isImageOnly -> if (allowMultiple) "Select photos" else "Select photo"
            else -> if (allowMultiple) "Select items" else "Select media"
        }

        if (allowMultiple) {
            binding.tvSubtitle.visibility = View.VISIBLE
            binding.tvSubtitle.text = "0 selected"
            binding.btnDone.visibility = View.VISIBLE
            binding.btnDone.isEnabled = false
            binding.btnDone.alpha = 0.5f
            binding.btnDone.text = "Done"
        } else {
            binding.tvSubtitle.visibility = View.GONE
            binding.btnDone.visibility = View.GONE
        }
    }

    private fun setupAdapters() {
        // Grid setup
        mediaAdapter = MediaPickerAdapter(
            entries = emptyList(),
            allowMultiple = allowMultiple,
            spanCount = 3,
            onItemClick = { entry ->
                // Single item selection: immediately return
                returnResult(listOf(entry))
            },
            onSelectionChanged = { selectedSet ->
                val count = selectedSet.size
                binding.tvSubtitle.text = "$count selected"
                binding.btnDone.isEnabled = count > 0
                binding.btnDone.alpha = if (count > 0) 1.0f else 0.5f
                binding.btnDone.text = if (count > 0) "Done ($count)" else "Done"
            }
        )

        binding.rvPickerGrid.layoutManager = GridLayoutManager(this, 3)
        binding.rvPickerGrid.setHasFixedSize(true)
        binding.rvPickerGrid.setItemViewCacheSize(20)
        binding.rvPickerGrid.adapter = mediaAdapter

        FastScrollHelper(
            recyclerView = binding.rvPickerGrid,
            thumbView = binding.fastScrollThumb,
            dateBubble = binding.tvFastScrollDate,
            getDateAtPosition = { pos ->
                currentDisplayedEntries.getOrNull(pos)?.let { it.dateModified * 1000L }
            }
        )

        // Filter chips setup
        chipAdapter = FilterChipAdapter(
            chips = listOf(FilterChip("all", "All Media")),
            selectedId = "all",
            onChipSelected = { chip ->
                selectedChipId = chip.id
                filterCurrentMediaByChip(chip)
            }
        )
        binding.rvFilterChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFilterChips.adapter = chipAdapter
    }

    private fun setupListeners() {
        binding.btnClose.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnDone.setOnClickListener {
            val selected = mediaAdapter.selectedEntries.toList()
            if (selected.isNotEmpty()) {
                returnResult(selected)
            }
        }

        binding.btnGrantPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                PermissionManager.requestManageStoragePermission(this)
            } else {
                permissionLauncher.launch(PermissionManager.getRequiredPermissions())
            }
        }
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedChipId != "all") {
                    // Reset to All Media chip first
                    selectedChipId = "all"
                    chipAdapter.setSelected("all")
                    currentDisplayedEntries = allFilteredEntries
                    mediaAdapter.updateEntries(currentDisplayedEntries)
                    binding.rvPickerGrid.scrollToPosition(0)
                    return
                }
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        })
    }

    private fun checkPermissionsAndLoad() {
        if (!PermissionManager.hasPermissions(this)) {
            showPermissionDeniedUI()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                PermissionManager.requestManageStoragePermission(this)
            } else {
                permissionLauncher.launch(PermissionManager.getRequiredPermissions())
            }
        } else {
            showMediaContent()
            observeMedia()
            repository.loadInitialMedia()
        }
    }

    private fun showPermissionDeniedUI() {
        binding.layoutPermissionDenied.visibility = View.VISIBLE
        binding.rvPickerGrid.visibility = View.GONE
        binding.rvFilterChips.visibility = View.GONE
    }

    private fun showMediaContent() {
        binding.layoutPermissionDenied.visibility = View.GONE
        binding.rvPickerGrid.visibility = View.VISIBLE
        binding.rvFilterChips.visibility = View.VISIBLE
    }

    private fun observeMedia() {
        lifecycleScope.launch {
            repository.mediaFlow.collect { allMedia ->
                val filtered = filterEntriesByMime(allMedia)
                allFilteredEntries = filtered

                updateFilterChips(filtered)

                // Apply currently selected chip
                val currentChip = chipAdapter.let {
                    FilterChip(selectedChipId, "", if (selectedChipId == "all") null else selectedChipId)
                }
                filterCurrentMediaByChip(currentChip)
            }
        }
    }

    private fun updateFilterChips(mediaList: List<FileEntry>) {
        val chips = mutableListOf<FilterChip>()
        chips.add(FilterChip("all", "All Media"))

        // Group by folder path
        val folderGroups = mediaList.groupBy { FileUtils.getAlbumRelativePath(it.relativePath) }
        val aliases = com.sk.gallery.data.local.AppPreferences(this).getAlbumAliases()

        for ((relPath, entries) in folderGroups) {
            if (entries.isEmpty()) continue
            val folderName = FileUtils.extractFolderName(entries.first().relativePath)
            val displayTitle = aliases[relPath] ?: folderName
            chips.add(FilterChip(id = relPath, name = "$displayTitle (${entries.size})", relativePath = relPath))
        }

        chipAdapter.setChips(chips, selectedChipId)
    }

    private fun filterCurrentMediaByChip(chip: FilterChip) {
        currentDisplayedEntries = if (chip.id == "all" || chip.relativePath.isNullOrEmpty()) {
            allFilteredEntries
        } else {
            allFilteredEntries.filter { it.relativePath.startsWith(chip.relativePath, ignoreCase = true) }
        }

        mediaAdapter.updateEntries(currentDisplayedEntries)
        binding.tvEmpty.visibility = if (currentDisplayedEntries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun matchesMimeType(entryMime: String, pattern: String): Boolean {
        if (pattern == "*/*" || pattern.isBlank()) return true
        if (pattern.endsWith("/*")) {
            val prefix = pattern.substringBefore("/*")
            return entryMime.startsWith("$prefix/", ignoreCase = true)
        }
        return entryMime.equals(pattern, ignoreCase = true)
    }

    private fun filterEntriesByMime(entries: List<FileEntry>): List<FileEntry> {
        val extraMimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        val baseType = intent.type

        val allowedMimes = when {
            !extraMimes.isNullOrEmpty() -> extraMimes.toList()
            !baseType.isNullOrBlank() && baseType != "*/*" -> listOf(baseType)
            else -> null
        }

        return if (allowedMimes != null) {
            entries.filter { entry ->
                allowedMimes.any { pattern -> matchesMimeType(entry.mimeType, pattern) }
            }
        } else {
            entries.filter {
                it.mimeType.startsWith("image/", ignoreCase = true) ||
                        it.mimeType.startsWith("video/", ignoreCase = true)
            }
        }
    }

    private fun returnResult(selected: List<FileEntry>) {
        if (selected.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val uris = selected.mapNotNull { entry ->
            getMediaUri(this, entry)
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "Failed to load selected media", Toast.LENGTH_SHORT).show()
            return
        }

        val firstUri = uris.first()
        val resultIntent = Intent().apply {
            data = firstUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val clipData = ClipData.newUri(contentResolver, "Selected Media", firstUri)
            for (i in 1 until uris.size) {
                clipData.addItem(ClipData.Item(uris[i]))
            }
            this.clipData = clipData
        }

        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun getMediaUri(context: Context, entry: FileEntry): Uri {
        val file = File(Environment.getExternalStorageDirectory(), entry.relativePath)
        val isVideo = entry.mimeType.startsWith("video/", ignoreCase = true)
        val primaryCollection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DATA} = ?"
            val selectionArgs = arrayOf(file.absolutePath)

            context.contentResolver.query(primaryCollection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(primaryCollection, id)
                }
            }

            // Fallback to Files table
            val filesCollection = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(filesCollection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(filesCollection, id)
                }
            }
        } catch (e: Exception) {
            // Ignore and fallback to FileProvider
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}
