package com.sk.gallery.ui.viewer

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sk.gallery.R
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.databinding.ActivityPhotoViewerBinding
import com.sk.gallery.databinding.DialogExifAboutBinding
import com.sk.gallery.databinding.ItemPhotoPageBinding
import com.sk.gallery.model.FileEntry
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryBySK"
        var currentPhotoList: List<FileEntry> = emptyList()
        const val EXTRA_INITIAL_INDEX = "extra_initial_index"
    }

    private lateinit var binding: ActivityPhotoViewerBinding
    private lateinit var preferences: AppPreferences
    private lateinit var repository: MediaRepository
    private var photoList: MutableList<FileEntry> = mutableListOf()
    private var currentIndex: Int = 0
    private var adapter: PhotoPagerAdapter? = null
    private var areBarsVisible: Boolean = true
    private var isEditMode: Boolean = false
    private var hasUnsavedChanges: Boolean = false
    private var currentToast: android.widget.Toast? = null
    
    // Edit State
    private var currentBitmap: android.graphics.Bitmap? = null
    private var activeSubCategory: String = ""
    private var currentSelectedTextView: android.view.View? = null
    private var imageClipRect: android.graphics.Rect? = null
    
    // Inline Text Input State
    private var isTextInputActive: Boolean = false
    private var textInputTapX: Float = -1f
    private var textInputTapY: Float = -1f
    private var editingExistingTextView: android.view.View? = null
    private var isHighlightMode: Boolean = false
    private var selectedFontColor: Int = android.graphics.Color.WHITE
    private var selectedHighlightColor: Int = android.graphics.Color.TRANSPARENT
    
    // Adjustment State
    private var adjBrightness = 0f
    private var adjContrast = 1f
    private var adjSaturation = 1f
    private var adjFilter = "NONE"
    private var activeAdjustCategory = "BRIGHTNESS"
    private var adjustOriginalBitmap: android.graphics.Bitmap? = null
    private var adjustedBitmap: android.graphics.Bitmap? = null
    private var adjustJob: kotlinx.coroutines.Job? = null
    private var filterThumbnailBitmap: android.graphics.Bitmap? = null

    data class FilterPreset(
        val id: String,
        val name: String,
        val matrix: android.graphics.ColorMatrix
    )

    private val filterPresets by lazy {
        listOf(
            FilterPreset("NONE", "Original", getFilterMatrix("NONE")),
            FilterPreset("VINTAGE", "Vintage", getFilterMatrix("VINTAGE")),
            FilterPreset("FILM", "Film", getFilterMatrix("FILM")),
            FilterPreset("SEPIA", "Sepia", getFilterMatrix("SEPIA")),
            FilterPreset("GRAYSCALE", "Grayscale", getFilterMatrix("GRAYSCALE")),
            FilterPreset("INVERT", "Invert", getFilterMatrix("INVERT")),
            FilterPreset("COOL", "Cool", getFilterMatrix("COOL")),
            FilterPreset("WARM", "Warm", getFilterMatrix("WARM"))
        )
    }

    private fun getFilterMatrix(id: String): android.graphics.ColorMatrix {
        val matrix = android.graphics.ColorMatrix()
        when (id) {
            "NONE" -> {}
            "VINTAGE" -> {
                matrix.set(floatArrayOf(
                    0.9f, 0.1f, 0.1f, 0f, 0f,
                    0.1f, 0.9f, 0.1f, 0f, 0f,
                    0.05f, 0.05f, 0.7f, 0f, 0f,
                    0f,    0f,    0f,    1f, 0f
                ))
            }
            "FILM" -> {
                matrix.set(floatArrayOf(
                    0.85f, 0.1f, 0.05f, 0f, 10f,
                    0.05f, 0.9f, 0.05f, 0f, 5f,
                    0.05f, 0.05f, 0.9f, 0f, 0f,
                    0f,    0f,    0f,    1f, 0f
                ))
            }
            "SEPIA" -> {
                matrix.set(floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f,     0f,     0f,     1f, 0f
                ))
            }
            "GRAYSCALE" -> {
                matrix.setSaturation(0f)
            }
            "INVERT" -> {
                matrix.set(floatArrayOf(
                    -1f, 0f,  0f,  0f, 255f,
                    0f,  -1f, 0f,  0f, 255f,
                    0f,  0f,  -1f, 0f, 255f,
                    0f,  0f,  0f,  1f, 0f
                ))
            }
            "COOL" -> {
                matrix.set(floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 0.9f, 0.1f, 0f, 0f,
                    0f, 0f, 1.2f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            "WARM" -> {
                matrix.set(floatArrayOf(
                    1.2f, 0f, 0f, 0f, 10f,
                    0f, 1.1f, 0f, 0f, 5f,
                    0f, 0f, 0.8f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
        }
        return matrix
    }

    private lateinit var mPhotoEditor: ja.burhanrashid52.photoeditor.PhotoEditor
    private lateinit var textGestureDetector: android.view.GestureDetector

    private val videoEditLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = AppPreferences(this)
        repository = MediaRepository.getInstance(this)
        photoList = currentPhotoList.toMutableList()
        currentIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)
        val isPrivateMode = intent.getBooleanExtra("EXTRA_IS_PRIVATE", false)
        
        if (isPrivateMode) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (photoList.isEmpty()) {
            finish()
            return
        }

        setupViewPager()
        setupTopBar()
        setupBottomBar(isPrivateMode)
        updateCurrentPhotoUI(currentIndex)
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTextInputActive) {
                    closeTextInput(save = false, deleteOnDiscard = false)
                } else if (isEditMode) {
                    exitEditModeWithPrompt()
                } else {
                    finishWithZoomOut()
                }
            }
        })
    }

    private fun setupViewPager() {
        adapter = PhotoPagerAdapter(photoList)
        binding.viewerViewPager.adapter = adapter
        binding.viewerViewPager.setCurrentItem(currentIndex, false)

        binding.viewerViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentIndex = position
                updateCurrentPhotoUI(position)
            }
        })
    }

    private fun updateCurrentPhotoUI(position: Int) {
        if (position !in photoList.indices) return
        val entry = photoList[position]
        binding.tvTitle.text = entry.fileName

        val isFav = preferences.isFavorite(entry.hashId)
        binding.ivFavouriteIcon.setColorFilter(
            if (isFav) getColor(android.R.color.holo_red_light) else getColor(android.R.color.white)
        )

        updateBarsVisibility()
    }

    private fun toggleBarsVisibility() {
        if (isEditMode) return // Don't toggle on tap during edit mode
        areBarsVisible = !areBarsVisible
        updateBarsVisibility()
    }

    private fun updateBarsVisibility() {
        if (isEditMode) return
        val visibility = if (areBarsVisible) View.VISIBLE else View.GONE
        binding.viewerTopBar.visibility = visibility
        binding.viewerBottomBar.visibility = visibility
    }

    private fun setupTopBar() {
        binding.btnBack.setOnClickListener {
            finishWithZoomOut()
        }

        binding.btnViewerMenu.setOnClickListener {
            showAboutBottomSheet()
        }

        binding.btnEditMainCancel.setOnClickListener {
            exitEditModeWithPrompt()
        }

        binding.btnEditMainSave.setOnClickListener {
            // Save button is only visible when hasUnsavedChanges is true
            showSaveDialog()
        }

        binding.btnEditSubCancel.setOnClickListener {
            exitSubCategory(false)
        }

        binding.btnEditSubConfirm.setOnClickListener {
            if (activeSubCategory == "CROP" || activeSubCategory == "ROTATE") {
                // Trigger crop async
                binding.cropImageView.croppedImageAsync()
            } else if (activeSubCategory == "DRAW" || activeSubCategory == "TEXT") {
                // Bake PhotoEditor layers into currentBitmap
                lifecycleScope.launch {
                    try {
                        val fullBitmap = mPhotoEditor.saveAsBitmap()
                        if (fullBitmap != null) {
                            currentBitmap = fullBitmap
                            exitSubCategory(true)
                        } else {
                            showToast("Failed to apply edits")
                            exitSubCategory(false)
                        }
                    } catch (e: Exception) {
                        showToast("Error: ${e.message}")
                        exitSubCategory(false)
                    }
                }
            } else if (activeSubCategory == "ADJUST") {
                if (adjustedBitmap != null) {
                    currentBitmap = adjustedBitmap
                    exitSubCategory(true)
                } else {
                    exitSubCategory(false)
                }
            }
        }
        
        setupPhotoEditor()
        setupEditCategories()
        setupEditTools()
    }

    private fun setupPhotoEditor() {
        mPhotoEditor = ja.burhanrashid52.photoeditor.PhotoEditor.Builder(this, binding.editMainImageView)
            .setPinchTextScalable(true)
            .build()
            
        mPhotoEditor.setShape(ja.burhanrashid52.photoeditor.shape.ShapeBuilder()
            .withShapeColor(android.graphics.Color.WHITE)
            .withShapeSize(10f))
            
        mPhotoEditor.setOnPhotoEditorListener(object : ja.burhanrashid52.photoeditor.OnPhotoEditorListener {
            override fun onEditTextChangeListener(rootView: android.view.View, text: String, colorCode: Int) {
                if (activeSubCategory != "TEXT") return
                
                if (isTextInputActive) {
                    closeTextInput(save = true)
                }

                if (currentSelectedTextView == rootView) {
                    // Re-edit selected text
                    openTextInput(-1f, -1f, rootView)
                } else {
                    // Select new text
                    currentSelectedTextView = rootView
                    syncColorStateFromSelectedText()
                }
            }
            override fun onAddViewListener(viewType: ja.burhanrashid52.photoeditor.ViewType, numberOfAddedViews: Int) {
                if (viewType == ja.burhanrashid52.photoeditor.ViewType.TEXT) {
                    binding.editMainImageView.post {
                        val childCount = binding.editMainImageView.childCount
                        if (childCount > 1) {
                            val newView = binding.editMainImageView.getChildAt(childCount - 1)
                            customizeStickerCloseButton(newView)
                            wrapStickerTouchListener(newView)
                        }
                    }
                }
            }
            override fun onRemoveViewListener(viewType: ja.burhanrashid52.photoeditor.ViewType, numberOfAddedViews: Int) {
                if (viewType == ja.burhanrashid52.photoeditor.ViewType.TEXT) {
                    currentSelectedTextView = null
                    syncColorStateFromSelectedText()
                }
            }
            override fun onStartViewChangeListener(viewType: ja.burhanrashid52.photoeditor.ViewType) {
                if (isTextInputActive) {
                    closeTextInput(save = true)
                }
            }
            override fun onStopViewChangeListener(viewType: ja.burhanrashid52.photoeditor.ViewType) {}
            override fun onTouchSourceImage(event: android.view.MotionEvent) {
                if (activeSubCategory == "TEXT" && event.action == android.view.MotionEvent.ACTION_DOWN) {
                    if (isTextInputActive) {
                        closeTextInput(save = false, deleteOnDiscard = false)
                    } else {
                        if (currentSelectedTextView != null) {
                            // Deselect
                            currentSelectedTextView = null
                            syncColorStateFromSelectedText()
                        } else {
                            // Add new text
                            openTextInput(event.x, event.y, null)
                        }
                    }
                }
            }
        })
        
        // No custom gesture detector needed on the image view, PhotoEditor handles it.
    }
    


    /**
     * Resizes the PhotoEditorView to exactly match the displayed image dimensions.
     * This prevents drawing outside the image bounds entirely.
     */
    private fun resizeEditorToFitImage() {
        val bmp = currentBitmap ?: return
        val topBarH = (60 * resources.displayMetrics.density).toInt()
        val bottomBarH = (64 * resources.displayMetrics.density).toInt()
        val availW = resources.displayMetrics.widthPixels
        val availH = resources.displayMetrics.heightPixels - topBarH - bottomBarH
        
        if (availW <= 0 || availH <= 0) return
        
        val scale = minOf(availW.toFloat() / bmp.width, availH.toFloat() / bmp.height)
        val displayW = (bmp.width * scale).toInt()
        val displayH = (bmp.height * scale).toInt()
        
        val params = binding.editMainImageView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.width = displayW
        params.height = displayH
        params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.topMargin = topBarH
        params.bottomMargin = bottomBarH
        // Remove old match_parent margins
        params.matchConstraintDefaultWidth = 0
        params.matchConstraintDefaultHeight = 0
        binding.editMainImageView.layoutParams = params
        binding.editMainImageView.source.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }
    
    /**
     * Resets the PhotoEditorView back to match_parent for proper cleanup.
     */
    private fun resetEditorSize() {
        val topBarH = (60 * resources.displayMetrics.density).toInt()
        val bottomBarH = (64 * resources.displayMetrics.density).toInt()
        val params = binding.editMainImageView.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.width = 0
        params.height = 0
        params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        params.topMargin = topBarH
        params.bottomMargin = bottomBarH
        binding.editMainImageView.layoutParams = params
    }

    private fun setupEditCategories() {
        binding.btnEditCategoryCrop.setOnClickListener {
            enterSubCategory("CROP")
        }
        binding.btnEditCategoryRotate.setOnClickListener {
            enterSubCategory("ROTATE")
        }
        binding.btnEditCategoryDraw.setOnClickListener {
            enterSubCategory("DRAW")
        }
        binding.btnEditCategoryText.setOnClickListener {
            enterSubCategory("TEXT")
        }
        binding.btnEditCategoryAdjust.setOnClickListener {
            enterSubCategory("ADJUST")
        }
    }

    private fun enterSubCategory(category: String) {
        activeSubCategory = category
        binding.editBottomBarMain.visibility = View.GONE
        binding.editTopBarMain.visibility = View.GONE
        binding.editMainImageView.visibility = View.GONE

        if (category == "CROP" || category == "ROTATE") {
            binding.cropImageView.visibility = View.VISIBLE
            currentBitmap?.let { binding.cropImageView.setImageBitmap(it) }
            
            binding.editTopBarSub.visibility = View.VISIBLE
            findViewById<android.view.View>(R.id.layout_draw_undo_redo).visibility = View.GONE
            
            if (category == "CROP") {
                binding.editBottomBarCrop.visibility = View.VISIBLE
                binding.cropImageView.isShowCropOverlay = true
            } else {
                binding.editBottomBarRotate.visibility = View.VISIBLE
                binding.cropImageView.isShowCropOverlay = false
                binding.seekBarRotate.progress = 45
                binding.tvRotateDegree.text = "0°"
            }
        } else if (category == "DRAW" || category == "TEXT") {
            binding.editMainImageView.visibility = android.view.View.VISIBLE
            binding.editTopBarSub.visibility = android.view.View.VISIBLE
            
            // Resize editor to fit image exactly — prevents drawing outside bounds
            resizeEditorToFitImage()
            
            val layoutDrawUndoRedo = findViewById<android.view.View>(R.id.layout_draw_undo_redo)
            if (category == "DRAW") {
                layoutDrawUndoRedo.visibility = android.view.View.VISIBLE
                binding.editBottomBarDraw.visibility = android.view.View.VISIBLE
                mPhotoEditor.setBrushDrawingMode(true)
            } else {
                layoutDrawUndoRedo.visibility = android.view.View.GONE
                binding.editBottomBarText.visibility = android.view.View.VISIBLE
                mPhotoEditor.setBrushDrawingMode(false)
                
                // Set default colors if none selected
                if (selectedFontColor == android.graphics.Color.TRANSPARENT) {
                    selectedFontColor = android.graphics.Color.WHITE
                }
                
                setupTextColorRows()
                openTextInput(-1f, -1f, null) // Open text input immediately in center
            }
        } else if (category == "ADJUST") {
            binding.editMainImageView.visibility = android.view.View.VISIBLE
            binding.editTopBarSub.visibility = android.view.View.VISIBLE
            
            // Resize editor to fit image exactly
            resizeEditorToFitImage()
            
            findViewById<android.view.View>(R.id.layout_draw_undo_redo).visibility = android.view.View.GONE
            binding.editBottomBarAdjust.visibility = android.view.View.VISIBLE
            
            // Reset adjustment state
            adjustOriginalBitmap = currentBitmap
            adjustedBitmap = null
            adjBrightness = 0f
            adjContrast = 1f
            adjSaturation = 1f
            adjFilter = "NONE"
            
            // Create scaled thumbnail for filters preview
            val orig = currentBitmap
            if (orig != null) {
                val size = (64 * resources.displayMetrics.density).toInt()
                filterThumbnailBitmap = android.graphics.Bitmap.createScaledBitmap(orig, size, size, true)
            } else {
                filterThumbnailBitmap = null
            }
            
            // Configure filters Recycler View
            val rvFilters = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_adjust_filters)
            rvFilters.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            rvFilters.adapter = FilterAdapter(this, filterThumbnailBitmap, filterPresets, adjFilter) { preset ->
                applyAdjustFilter(preset.id)
            }
            
            selectAdjustCategory("BRIGHTNESS")
        }
    }

    private fun exitSubCategory(saveChanges: Boolean) {
        if (isTextInputActive) {
            closeTextInput(save = saveChanges)
        }
        
        binding.editBottomBarCrop.visibility = View.GONE
        binding.editBottomBarRotate.visibility = View.GONE
        binding.editBottomBarDraw.visibility = View.GONE
        binding.editBottomBarText.visibility = View.GONE
        binding.editBottomBarAdjust.visibility = View.GONE
        binding.editTopBarSub.visibility = View.GONE
        binding.cropImageView.visibility = View.GONE

        binding.editTopBarMain.visibility = View.VISIBLE
        binding.editBottomBarMain.visibility = View.VISIBLE
        binding.editMainImageView.visibility = View.VISIBLE
        
        // Reset editor to fill available space on main edit screen
        resetEditorSize()
        
        try { mPhotoEditor.setBrushDrawingMode(false) } catch (_: Exception) {}
        
        if (saveChanges && currentBitmap != null) {
            hasUnsavedChanges = true
            binding.editMainImageView.source.setImageBitmap(currentBitmap)
            try { mPhotoEditor.clearAllViews() } catch (_: Exception) {}
        } else {
            // Discarding changes
            try { mPhotoEditor.clearAllViews() } catch (_: Exception) {}
            currentBitmap?.let { binding.editMainImageView.source.setImageBitmap(it) }
        }
        
        try {
            binding.cropImageView.setImageBitmap(null)
        } catch (_: Exception) {}
        
        activeSubCategory = ""
        currentSelectedTextView = null
        
        // Update Save button visibility
        binding.btnEditMainSave.visibility = if (hasUnsavedChanges) View.VISIBLE else View.GONE
    }

    private fun setupEditTools() {
        // Crop Tools
        binding.btnCropFree.setOnClickListener {
            binding.cropImageView.setFixedAspectRatio(false)
        }
        binding.btnCrop11.setOnClickListener {
            binding.cropImageView.setAspectRatio(1, 1)
            binding.cropImageView.setFixedAspectRatio(true)
        }
        binding.btnCrop169.setOnClickListener {
            binding.cropImageView.setAspectRatio(16, 9)
            binding.cropImageView.setFixedAspectRatio(true)
        }
        
        // Rotate Tools
        binding.btnRotate90.setOnClickListener {
            binding.cropImageView.rotateImage(90)
        }
        binding.btnRotateMirror.setOnClickListener {
            binding.cropImageView.flipImageHorizontally()
        }
        
        // Seekbar logic for rotation
        binding.seekBarRotate.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val degrees = progress - 45
                    binding.cropImageView.rotatedDegrees = degrees
                    binding.tvRotateDegree.text = "${degrees}°"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        
        binding.cropImageView.setOnCropImageCompleteListener { _, result ->
            if (result.isSuccessful && result.bitmap != null) {
                currentBitmap = result.bitmap
                exitSubCategory(true)
            } else {
                showToast("Crop failed.")
                exitSubCategory(false)
            }
        }
        
        // Draw Tools
        findViewById<android.view.View>(R.id.btn_draw_undo).setOnClickListener { mPhotoEditor.undo() }
        findViewById<android.view.View>(R.id.btn_draw_redo).setOnClickListener { mPhotoEditor.redo() }
        
        val colors = listOf(
            android.graphics.Color.WHITE, android.graphics.Color.BLACK, android.graphics.Color.RED, 
            android.graphics.Color.BLUE, android.graphics.Color.GREEN, android.graphics.Color.YELLOW
        )
        val rvDrawColors = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_draw_colors)
        val seekBarBrushSize = findViewById<android.widget.SeekBar>(R.id.seek_bar_brush_size)
        val tvBrushSizeVal = findViewById<android.widget.TextView>(R.id.tv_brush_size_val)
        val layoutBrushSize = findViewById<android.view.View>(R.id.layout_brush_size)
        
        rvDrawColors.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        rvDrawColors.adapter = ColorAdapter(colors) { color ->
            mPhotoEditor.setShape(ja.burhanrashid52.photoeditor.shape.ShapeBuilder()
                .withShapeColor(color)
                .withShapeSize(seekBarBrushSize.progress.toFloat()))
        }
        
        findViewById<android.view.View>(R.id.btn_draw_brush_toggle).setOnClickListener {
            layoutBrushSize.visibility = if (layoutBrushSize.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        
        seekBarBrushSize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvBrushSizeVal.text = progress.toString()
                    val color = (rvDrawColors.adapter as? ColorAdapter)?.getSelectedColor() ?: android.graphics.Color.WHITE
                    mPhotoEditor.setShape(ja.burhanrashid52.photoeditor.shape.ShapeBuilder()
                        .withShapeColor(color)
                        .withShapeSize(progress.toFloat()))
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        findViewById<android.view.View>(R.id.btn_draw_color_picker).setOnClickListener {
            com.skydoves.colorpickerview.ColorPickerDialog.Builder(this)
                .setTitle("ColorPicker")
                .setPositiveButton("Confirm", com.skydoves.colorpickerview.listeners.ColorEnvelopeListener { envelope, _ ->
                    val color = envelope.color
                    (rvDrawColors.adapter as? ColorAdapter)?.addAndSelectColor(color)
                })
                .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                .attachAlphaSlideBar(false)
                .attachBrightnessSlideBar(true)
                .show()
        }
        
        // Text Tools (Inline Input)
        findViewById<android.view.View>(R.id.btn_text_highlight_toggle).setOnClickListener {
            isHighlightMode = !isHighlightMode
            val btn = findViewById<android.widget.ImageView>(R.id.btn_text_highlight_toggle)
            btn.alpha = if (isHighlightMode) 1.0f else 0.5f
            
            if (isHighlightMode && selectedHighlightColor == android.graphics.Color.TRANSPARENT) {
                selectedHighlightColor = android.graphics.Color.BLACK
            }
            
            findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_text_highlight_colors).visibility = 
                if (isHighlightMode) android.view.View.VISIBLE else android.view.View.GONE
                
            setupTextColorRows()
            updateLiveTextColors()
        }
        
        findViewById<android.view.View>(R.id.btn_text_done).setOnClickListener {
            closeTextInput(save = true, deleteOnDiscard = false)
        }
        
        findViewById<android.view.View>(R.id.btn_text_remove).setOnClickListener {
            closeTextInput(save = false, deleteOnDiscard = true)
        }
        
        setupAdjustTools()
    }

    private fun setupAdjustTools() {
        val seekBarAdjust = findViewById<android.widget.SeekBar>(R.id.seek_bar_adjust)
        
        // Setup individual category button click listeners
        findViewById<android.view.View>(R.id.btn_adjust_brightness).setOnClickListener {
            selectAdjustCategory("BRIGHTNESS")
        }
        findViewById<android.view.View>(R.id.btn_adjust_contrast).setOnClickListener {
            selectAdjustCategory("CONTRAST")
        }
        findViewById<android.view.View>(R.id.btn_adjust_saturation).setOnClickListener {
            selectAdjustCategory("SATURATION")
        }
        findViewById<android.view.View>(R.id.btn_adjust_filter).setOnClickListener {
            selectAdjustCategory("FILTER")
        }

        // SeekBar changes
        seekBarAdjust.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val tvAdjustValue = findViewById<android.widget.TextView>(R.id.tv_adjust_value)
                    val valueText: String
                    when (activeAdjustCategory) {
                        "BRIGHTNESS" -> {
                            val deviation = progress - 50 // -50 to 50
                            adjBrightness = deviation * 2.55f // maps to -127.5f to 127.5f
                            valueText = if (deviation > 0) "+$deviation" else deviation.toString()
                        }
                        "CONTRAST" -> {
                            // range 0.5 to 1.5, progress 50 is 1.0
                            val scale = 0.5f + (progress / 100f)
                            adjContrast = scale
                            val displayVal = progress - 50
                            valueText = if (displayVal > 0) "+$displayVal" else displayVal.toString()
                        }
                        "SATURATION" -> {
                            // range 0.0 to 2.0, progress 50 is 1.0
                            val scale = (progress / 50f)
                            adjSaturation = scale
                            val displayVal = progress - 50
                            valueText = if (displayVal > 0) "+$displayVal" else displayVal.toString()
                        }
                        else -> {
                            valueText = "0"
                        }
                    }
                    tvAdjustValue.text = valueText
                    triggerApplyAdjustments()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun selectAdjustCategory(category: String) {
        activeAdjustCategory = category
        val seekBarAdjust = findViewById<android.widget.SeekBar>(R.id.seek_bar_adjust)
        val tvAdjustLabel = findViewById<android.widget.TextView>(R.id.tv_adjust_label)
        val tvAdjustValue = findViewById<android.widget.TextView>(R.id.tv_adjust_value)
        val layoutSeekbar = findViewById<android.view.View>(R.id.layout_adjust_seekbar)
        val rvFilters = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_adjust_filters)

        if (category == "FILTER") {
            layoutSeekbar.visibility = android.view.View.GONE
            rvFilters.visibility = android.view.View.VISIBLE
        } else {
            layoutSeekbar.visibility = android.view.View.VISIBLE
            rvFilters.visibility = android.view.View.GONE
            
            // Capitalize category name for display
            tvAdjustLabel.text = category.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }

            when (category) {
                "BRIGHTNESS" -> {
                    val dev = (adjBrightness / 2.55f).toInt()
                    seekBarAdjust.progress = dev + 50
                    tvAdjustValue.text = if (dev > 0) "+$dev" else dev.toString()
                }
                "CONTRAST" -> {
                    val progress = ((adjContrast - 0.5f) * 100f).toInt()
                    seekBarAdjust.progress = progress
                    val displayVal = progress - 50
                    tvAdjustValue.text = if (displayVal > 0) "+$displayVal" else displayVal.toString()
                }
                "SATURATION" -> {
                    val progress = (adjSaturation * 50f).toInt()
                    seekBarAdjust.progress = progress
                    val displayVal = progress - 50
                    tvAdjustValue.text = if (displayVal > 0) "+$displayVal" else displayVal.toString()
                }
            }
        }
    }

    private fun applyAdjustFilter(filterType: String) {
        adjFilter = filterType
        triggerApplyAdjustments()
    }

    private fun triggerApplyAdjustments() {
        val orig = adjustOriginalBitmap ?: return
        adjustJob?.cancel()
        adjustJob = lifecycleScope.launch {
            val adjusted = withContext(kotlinx.coroutines.Dispatchers.Default) {
                applyColorMatrixAdjustments(orig)
            }
            if (isActive) {
                adjustedBitmap = adjusted
                binding.editMainImageView.source.setImageBitmap(adjusted)
            }
        }
    }

    private fun applyColorMatrixAdjustments(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val finalMatrix = android.graphics.ColorMatrix()
        
        // 1. Contrast
        val scale = adjContrast
        val translate = (-0.5f * scale + 0.5f) * 255f
        val contrastMat = android.graphics.ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        finalMatrix.postConcat(contrastMat)
        
        // 2. Saturation or Filter
        // 2. Filter Matrix
        val filterMat = getFilterMatrix(adjFilter)
        finalMatrix.postConcat(filterMat)
        
        // 3. Saturation (only if not Grayscale)
        if (adjSaturation != 1f && adjFilter != "GRAYSCALE") {
            val satMat = android.graphics.ColorMatrix()
            satMat.setSaturation(adjSaturation)
            finalMatrix.postConcat(satMat)
        }
        
        // 3. Brightness
        if (adjBrightness != 0f) {
            val brightnessMat = android.graphics.ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, adjBrightness,
                0f, 1f, 0f, 0f, adjBrightness,
                0f, 0f, 1f, 0f, adjBrightness,
                0f, 0f, 0f, 1f, 0f
            ))
            finalMatrix.postConcat(brightnessMat)
        }
        
        val bmp = android.graphics.Bitmap.createBitmap(source.width, source.height, source.config ?: android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(finalMatrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return bmp
    }
    
    // --- Inline Text Editor Logic ---
    
    private fun findTextView(view: android.view.View): android.widget.TextView? {
        if (view is android.widget.TextView) {
            return view
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val found = findTextView(child)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun openTextInput(x: Float, y: Float, existingTextView: android.view.View?) {
        isTextInputActive = true
        textInputTapX = x
        textInputTapY = y
        editingExistingTextView = existingTextView
        
        val overlay = findViewById<android.view.View>(R.id.text_input_overlay_container)
        val et = findViewById<android.widget.EditText>(R.id.et_inline_text_input)
        
        // Show input overlay and darken background
        overlay.setBackgroundColor(android.graphics.Color.parseColor("#90000000"))
        overlay.visibility = android.view.View.VISIBLE
        
        // Hide top bar when typing to avoid distraction
        binding.editTopBarSub.visibility = android.view.View.GONE
        
        // Show Done/Remove buttons in the bottom bar
        findViewById<android.view.View>(R.id.layout_text_input_actions).visibility = android.view.View.VISIBLE
        
        if (existingTextView != null) {
            // Re-editing - hide sticker from canvas so we only see the overlay typing
            existingTextView.visibility = android.view.View.INVISIBLE
            
            val textView = findTextView(existingTextView)
            var textStr = ""
            if (textView != null) {
                textStr = textView.text.toString()
                selectedFontColor = textView.currentTextColor
                val bg = textView.background
                if (bg is android.graphics.drawable.ColorDrawable) {
                    selectedHighlightColor = bg.color
                } else {
                    selectedHighlightColor = android.graphics.Color.TRANSPARENT
                }
            }
            et.setText(textStr)
            if (textStr.isNotEmpty()) {
                et.setSelection(textStr.length)
            }
            isHighlightMode = selectedHighlightColor != android.graphics.Color.TRANSPARENT
        } else {
            // New text
            et.setText("")
        }
        
        // Update highlight toggle state
        val btnToggle = findViewById<android.widget.ImageView>(R.id.btn_text_highlight_toggle)
        btnToggle.alpha = if (isHighlightMode) 1.0f else 0.5f
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_text_highlight_colors).visibility = 
            if (isHighlightMode) android.view.View.VISIBLE else android.view.View.GONE
        
        setupTextColorRows()
        updateLiveTextColors()
        
        // Keep the editor centered on the screen always
        et.translationX = 0f
        et.translationY = 0f
        
        // Show keyboard
        et.requestFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun closeTextInput(save: Boolean, deleteOnDiscard: Boolean = false) {
        val et = findViewById<android.widget.EditText>(R.id.et_inline_text_input)
        val text = et.text.toString().trim()
        
        // Hide keyboard
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(et.windowToken, 0)
        
        if (save && text.isNotEmpty()) {
            val styleBuilder = ja.burhanrashid52.photoeditor.TextStyleBuilder()
            styleBuilder.withTextColor(selectedFontColor)
            styleBuilder.withTextSize(40f)
            if (isHighlightMode && selectedHighlightColor != android.graphics.Color.TRANSPARENT) {
                styleBuilder.withBackgroundColor(selectedHighlightColor)
            }
            
            if (editingExistingTextView != null) {
                mPhotoEditor.editText(editingExistingTextView!!, text, styleBuilder)
                editingExistingTextView!!.visibility = android.view.View.VISIBLE
            } else {
                val localTapX = textInputTapX
                val localTapY = textInputTapY
                mPhotoEditor.addText(text, styleBuilder)
                
                // We want to move the newly added text to the tap position
                binding.editMainImageView.post {
                    val childCount = binding.editMainImageView.childCount
                    if (childCount > 1) {
                        val newView = binding.editMainImageView.getChildAt(childCount - 1)
                        if (localTapX >= 0 && localTapY >= 0) {
                            newView.translationX = localTapX - (binding.editMainImageView.width / 2f)
                            newView.translationY = localTapY - (binding.editMainImageView.height / 2f)
                        }
                        currentSelectedTextView = newView
                    }
                }
            }
        } else {
            // Cancelled or empty -> remove the sticker if it was existing and requested, otherwise restore visibility
            if (editingExistingTextView != null) {
                if (deleteOnDiscard) {
                    removeStickerView(editingExistingTextView!!)
                } else {
                    editingExistingTextView!!.visibility = android.view.View.VISIBLE
                }
            }
        }
        
        // Clean up input state
        isTextInputActive = false
        editingExistingTextView = null
        textInputTapX = -1f
        textInputTapY = -1f
        
        // Hide input overlay and clear background darkening
        val overlay = findViewById<android.view.View>(R.id.text_input_overlay_container)
        overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        overlay.visibility = android.view.View.GONE
        
        // Restore sub top bar
        binding.editTopBarSub.visibility = android.view.View.VISIBLE
        
        // Hide Done/Remove buttons
        findViewById<android.view.View>(R.id.layout_text_input_actions).visibility = android.view.View.GONE
    }
    
    private fun customizeStickerCloseButton(rootView: android.view.View) {
        val closeId = rootView.resources.getIdentifier("imgPhotoEditorClose", "id", rootView.context.packageName)
        if (closeId != 0) {
            val closeBtn = rootView.findViewById<android.view.View>(closeId)
            if (closeBtn != null) {
                val params = closeBtn.layoutParams
                if (params != null) {
                    val density = rootView.resources.displayMetrics.density
                    params.width = (48 * density).toInt()
                    params.height = (48 * density).toInt()
                    closeBtn.layoutParams = params
                }
                closeBtn.setPadding(12, 12, 12, 12)
            }
        }
    }
    
    private fun removeStickerView(rootView: android.view.View) {
        val closeId = rootView.resources.getIdentifier("imgPhotoEditorClose", "id", rootView.context.packageName)
        if (closeId != 0) {
            val closeBtn = rootView.findViewById<android.view.View>(closeId)
            if (closeBtn != null) {
                closeBtn.performClick()
                return
            }
        }
        binding.editMainImageView.removeView(rootView)
    }
    
    private fun wrapStickerTouchListener(rootView: android.view.View) {
        try {
            val getListenerInfo = android.view.View::class.java.getDeclaredMethod("getListenerInfo")
            getListenerInfo.isAccessible = true
            val listenerInfo = getListenerInfo.invoke(rootView) ?: return
            val listenerInfoClass = Class.forName("android.view.View\$ListenerInfo")
            val mOnTouchListenerField = listenerInfoClass.getDeclaredField("mOnTouchListener")
            mOnTouchListenerField.isAccessible = true
            val originalListener = mOnTouchListenerField.get(listenerInfo) as? android.view.View.OnTouchListener
            
            if (originalListener != null) {
                val gestureDetector = android.view.GestureDetector(rootView.context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                        if (activeSubCategory == "TEXT") {
                            if (currentSelectedTextView == rootView) {
                                openTextInput(-1f, -1f, rootView)
                            } else {
                                currentSelectedTextView = rootView
                                syncColorStateFromSelectedText()
                            }
                        }
                        return true
                    }
                })
                
                rootView.setOnTouchListener { v, event ->
                    gestureDetector.onTouchEvent(event)
                    originalListener.onTouch(v, event)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun updateLiveTextColors() {
        if (isTextInputActive) {
            val et = findViewById<android.widget.EditText>(R.id.et_inline_text_input)
            et.setTextColor(selectedFontColor)
            if (isHighlightMode) {
                et.setBackgroundColor(selectedHighlightColor)
            } else {
                et.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        } else if (currentSelectedTextView != null) {
            // Apply live color changes to selected text
            val styleBuilder = ja.burhanrashid52.photoeditor.TextStyleBuilder()
            styleBuilder.withTextColor(selectedFontColor)
            styleBuilder.withTextSize(40f)
            if (isHighlightMode && selectedHighlightColor != android.graphics.Color.TRANSPARENT) {
                styleBuilder.withBackgroundColor(selectedHighlightColor)
            }
            
            if (currentSelectedTextView is android.widget.FrameLayout) {
                var currentText = ""
                for (i in 0 until (currentSelectedTextView as android.widget.FrameLayout).childCount) {
                    val child = (currentSelectedTextView as android.widget.FrameLayout).getChildAt(i)
                    if (child is android.widget.TextView) {
                        currentText = child.text.toString()
                        break
                    }
                }
                mPhotoEditor.editText(currentSelectedTextView!!, currentText, styleBuilder)
            }
        }
    }
    
    private fun setupTextColorRows() {
        val colors = listOf(
            android.graphics.Color.WHITE, android.graphics.Color.BLACK, android.graphics.Color.RED, 
            android.graphics.Color.BLUE, android.graphics.Color.GREEN, android.graphics.Color.YELLOW,
            android.graphics.Color.MAGENTA, android.graphics.Color.CYAN, android.graphics.Color.GRAY
        )
        
        val rvFontColors = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_text_font_colors)
        val rvHighlightColors = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_text_highlight_colors)
        
        if (rvFontColors.layoutManager == null) {
            rvFontColors.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        }
        if (rvHighlightColors.layoutManager == null) {
            rvHighlightColors.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        }
        
        val fontAdapter = ColorAdapter(colors) { color ->
            selectedFontColor = color
            updateLiveTextColors()
        }
        if (selectedFontColor != android.graphics.Color.TRANSPARENT) {
            fontAdapter.addAndSelectColor(selectedFontColor, fireCallback = false)
        }
        rvFontColors.adapter = fontAdapter
        
        val highlightAdapter = ColorAdapter(colors) { color ->
            selectedHighlightColor = color
            updateLiveTextColors()
        }
        if (selectedHighlightColor != android.graphics.Color.TRANSPARENT) {
            highlightAdapter.addAndSelectColor(selectedHighlightColor, fireCallback = false)
        }
        rvHighlightColors.adapter = highlightAdapter
    }
    
    private fun syncColorStateFromSelectedText() {
        if (currentSelectedTextView is android.widget.FrameLayout) {
            for (i in 0 until (currentSelectedTextView as android.widget.FrameLayout).childCount) {
                val child = (currentSelectedTextView as android.widget.FrameLayout).getChildAt(i)
                if (child is android.widget.TextView) {
                    selectedFontColor = child.currentTextColor
                    val bg = child.background
                    if (bg is android.graphics.drawable.ColorDrawable) {
                        selectedHighlightColor = bg.color
                        isHighlightMode = true
                    } else {
                        selectedHighlightColor = android.graphics.Color.TRANSPARENT
                        isHighlightMode = false
                    }
                    break
                }
            }
        }
        
        val btnToggle = findViewById<android.widget.ImageView>(R.id.btn_text_highlight_toggle)
        btnToggle.alpha = if (isHighlightMode) 1.0f else 0.5f
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_text_highlight_colors).visibility = 
            if (isHighlightMode) android.view.View.VISIBLE else android.view.View.GONE
            
        setupTextColorRows()

    }

    private fun showToast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        currentToast?.show()
    }

    private fun setupBottomBar(isPrivateMode: Boolean) {
        if (isPrivateMode) {
            binding.btnShare.visibility = View.GONE
            binding.btnFavourite.visibility = View.GONE
            binding.btnMove.visibility = View.GONE
            val btnEdit = binding.viewerBottomBar.findViewById<View>(R.id.btn_edit)
            btnEdit?.visibility = View.GONE

            binding.btnDelete.visibility = View.VISIBLE
            binding.btnDelete.setOnClickListener {
                confirmDeletePrivatePhoto()
            }

            val tvPrivate = binding.btnPrivate.getChildAt(1) as android.widget.TextView
            tvPrivate.text = "Set Public"
            val ivPrivate = binding.btnPrivate.getChildAt(0) as android.widget.ImageView
            ivPrivate.setImageResource(R.drawable.ic_public_modern)

            binding.btnPrivate.setOnClickListener {
                setPublicCurrentPhoto()
            }
        } else {
            binding.btnShare.setOnClickListener { shareCurrentPhoto() }
            binding.btnFavourite.setOnClickListener { toggleCurrentFavourite() }
            binding.btnMove.setOnClickListener { moveCurrentPhoto() }
            binding.btnPrivate.setOnClickListener { toggleCurrentPrivate() }
            binding.btnDelete.setOnClickListener { confirmDeleteCurrentPhoto() }
            
            // Note: The edit button is newly added to the layout so we find it safely
            val btnEdit = binding.viewerBottomBar.findViewById<View>(R.id.btn_edit)
            btnEdit?.setOnClickListener { enterEditMode() }
        }
    }

    private fun setPublicCurrentPhoto() {
        val entry = getCurrentEntry() ?: return
        
        // Show restore dialog
        AlertDialog.Builder(this)
            .setTitle("Set as Public")
            .setMessage("Where do you want to restore this file?")
            .setPositiveButton("Original Location") { _, _ ->
                com.sk.gallery.data.PrivateVaultManager.restoreFromVault(this, listOf(entry.hashId)) {
                    Toast.makeText(this, "Restored to public gallery", Toast.LENGTH_SHORT).show()
                    removeCurrentFromList()
                }
            }
            .setNeutralButton("Custom Location") { _, _ ->
                isLaunchingPicker = true
                com.sk.gallery.util.MoveHelper.showLocationPicker(this) { targetDir ->
                    isLaunchingPicker = false
                    com.sk.gallery.data.PrivateVaultManager.restoreFromVault(this, listOf(entry.hashId), targetDir) {
                        Toast.makeText(this, "Restored to custom location", Toast.LENGTH_SHORT).show()
                        removeCurrentFromList()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeCurrentFromList() {
        photoList.removeAt(currentIndex)
        if (photoList.isEmpty()) {
            finishWithZoomOut()
        } else {
            if (currentIndex >= photoList.size) {
                currentIndex = photoList.size - 1
            }
            adapter?.notifyDataSetChanged()
            binding.viewerViewPager.setCurrentItem(currentIndex, true)
            updateCurrentPhotoUI(currentIndex)
        }
    }

    private fun getCurrentEntry(): FileEntry? {
        return photoList.getOrNull(currentIndex)
    }

    private fun shareCurrentPhoto() {
        val entry = getCurrentEntry() ?: return
        val file = File(Environment.getExternalStorageDirectory(), entry.relativePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not available on local storage", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Image"))
    }

    private fun toggleCurrentFavourite() {
        val entry = getCurrentEntry() ?: return
        val isFav = preferences.toggleFavorite(entry.hashId)
        updateCurrentPhotoUI(currentIndex)
        repository.notifyFavoritesChanged()
        val msg = if (isFav) "Added to Favourites" else "Removed from Favourites"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun moveCurrentPhoto() {
        val entry = getCurrentEntry() ?: return
        com.sk.gallery.util.MoveHelper.showMoveDialog(this, listOf(entry)) {
            finish()
        }
    }

    private fun toggleCurrentPrivate() {
        val entry = getCurrentEntry() ?: return
        com.sk.gallery.data.PrivateVaultManager.moveToVault(this, listOf(entry)) {
            Toast.makeText(this, "Moved to Private Vault", Toast.LENGTH_SHORT).show()
            removeCurrentFromList()
        }
    }

    private fun confirmDeleteCurrentPhoto() {
        val entry = getCurrentEntry() ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete ${entry.fileName}?")
            .setPositiveButton("Delete") { _, _ ->
                deleteCurrentPhoto(entry)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePrivatePhoto() {
        val entry = getCurrentEntry() ?: return
        AlertDialog.Builder(this)
            .setTitle("Permanently Delete?")
            .setMessage("Are you sure you want to permanently delete this file? This action cannot be undone and the file will not be moved to Recently Deleted.")
            .setPositiveButton("Delete") { _, _ ->
                val internalFileName = entry.hashId
                val deleted = com.sk.gallery.data.PrivateVaultManager.deleteFromVault(this, internalFileName)
                if (deleted) {
                    Toast.makeText(this, "Deleted permanently", Toast.LENGTH_SHORT).show()
                    removeCurrentFromList()
                } else {
                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrentPhoto(entry: FileEntry) {
        lifecycleScope.launch {
            com.sk.gallery.data.trash.TrashManager.moveToTrash(this@PhotoViewerActivity, listOf(entry)) {
                Toast.makeText(this@PhotoViewerActivity, "Moved to Recently Deleted", Toast.LENGTH_SHORT).show()
                photoList.removeAt(currentIndex)
                if (photoList.isEmpty()) {
                    finishWithZoomOut()
                } else {
                    if (currentIndex >= photoList.size) {
                        currentIndex = photoList.size - 1
                    }
                    adapter?.notifyDataSetChanged()
                    binding.viewerViewPager.setCurrentItem(currentIndex, true)
                    updateCurrentPhotoUI(currentIndex)
                }
            }
        }
    }

    private fun showAboutBottomSheet() {
        val entry = getCurrentEntry() ?: return
        val dialog = BottomSheetDialog(this)
        val sheetBinding = DialogExifAboutBinding.inflate(layoutInflater)

        sheetBinding.tvDetailFilename.text = entry.fileName
        sheetBinding.tvDetailPath.text = "Location: ${entry.relativePath}"

        val dateStr = if (entry.dateModified > 0) {
            SimpleDateFormat("MMMM dd, yyyy hh:mm a", Locale.getDefault()).format(Date(entry.dateModified * 1000L))
        } else "Unknown Date"
        sheetBinding.tvDetailDate.text = "Created: $dateStr"

        val sizeMb = "%.2f MB".format(entry.sizeBytes / (1024.0 * 1024.0))
        sheetBinding.tvDetailSize.text = "Size: $sizeMb"

        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun enterEditMode() {
        val entry = getCurrentEntry() ?: return
        val isVideo = entry.mimeType.startsWith("video", ignoreCase = true)
        
        if (isVideo) {
            val file = File(Environment.getExternalStorageDirectory(), entry.relativePath)
            val path = if (file.exists()) file.absolutePath else entry.relativePath
            val intent = Intent(this, VideoEditorActivity::class.java).apply {
                putExtra("VIDEO_PATH", path)
            }
            videoEditLauncher.launch(intent)
            return
        }
    
        isEditMode = true
        binding.viewerViewPager.isUserInputEnabled = false // Disable swiping pages during edit
        binding.btnEditMainSave.visibility = View.GONE

        val fullModel = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(entry, false)
        val thumbModel = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(entry, true)
        
        // Load bitmap via Glide for the main edit view
        Glide.with(this)
            .asBitmap()
            .load(fullModel)
            .thumbnail(Glide.with(this).asBitmap().load(thumbModel))
            .signature(com.bumptech.glide.signature.ObjectKey(entry.dateModified))
            .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                    currentBitmap = resource
                    binding.editMainImageView.source.setImageBitmap(resource)
                    binding.editMainImageView.visibility = View.VISIBLE
                    binding.viewerViewPager.visibility = View.INVISIBLE

                    // Hide viewer bars, show edit bars
                    binding.viewerTopBar.visibility = View.GONE
                    binding.viewerTopBar.translationY = 0f
                    binding.viewerBottomBar.visibility = View.GONE
                    binding.viewerBottomBar.translationY = 0f

                    binding.editTopBarMain.visibility = View.VISIBLE
                    binding.editTopBarMain.translationY = 0f
                    binding.editBottomBarMain.visibility = View.VISIBLE
                    binding.editBottomBarMain.translationY = 0f
                    
                    // Resize the editor to fit image exactly
                    binding.editMainImageView.post {
                        resizeEditorToFitImage()
                    }
                }
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })
            
        hasUnsavedChanges = false
        binding.btnEditMainSave.visibility = View.GONE
    }

    private fun exitEditModeWithPrompt() {
        if (activeSubCategory.isNotEmpty()) {
            // If in a sub-category, just exit the sub-category
            exitSubCategory(false)
            return
        }
        
        if (hasUnsavedChanges) {
            AlertDialog.Builder(this)
                .setTitle("Discard Changes?")
                .setMessage("You have unsaved edits. Are you sure you want to exit?")
                .setPositiveButton("Discard") { _, _ -> exitEditMode() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            exitEditMode()
        }
    }

    private fun exitEditMode() {
        isEditMode = false
        hasUnsavedChanges = false
        binding.viewerViewPager.isUserInputEnabled = true
        
        // Safely clean up all editor state
        try { mPhotoEditor.setBrushDrawingMode(false) } catch (_: Exception) {}
        try { mPhotoEditor.clearAllViews() } catch (_: Exception) {}
        try { binding.cropImageView.setImageBitmap(null) } catch (_: Exception) {}
        try { binding.editMainImageView.source.setImageBitmap(null) } catch (_: Exception) {}
        
        currentBitmap = null
        activeSubCategory = ""
        currentSelectedTextView = null
        imageClipRect = null
        resetEditorSize()
        
        // Immediately hide ALL edit UI elements
        binding.editTopBarMain.visibility = View.GONE
        binding.editTopBarMain.translationY = 0f
        binding.editBottomBarMain.visibility = View.GONE
        binding.editBottomBarMain.translationY = 0f
        binding.editTopBarSub.visibility = View.GONE
        binding.editBottomBarCrop.visibility = View.GONE
        binding.editBottomBarRotate.visibility = View.GONE
        binding.editBottomBarDraw.visibility = View.GONE
        binding.editBottomBarText.visibility = View.GONE
        binding.btnEditMainSave.visibility = View.GONE
        
        binding.editMainImageView.visibility = View.GONE
        binding.cropImageView.visibility = View.GONE
        
        // Show viewer
        binding.viewerViewPager.visibility = View.VISIBLE
        
        binding.viewerTopBar.visibility = View.VISIBLE
        binding.viewerTopBar.translationY = 0f
        
        binding.viewerBottomBar.visibility = View.VISIBLE
        binding.viewerBottomBar.translationY = 0f
    }

    private var pendingReplaceOriginal = false

    private fun showSaveDialog() {
        AlertDialog.Builder(this)
            .setTitle("Save Edits")
            .setMessage("Do you want to replace the original file or save as a copy?")
            .setPositiveButton("Replace Original") { _, _ ->
                saveEdits(replaceOriginal = true)
            }
            .setNeutralButton("Save as Copy") { _, _ ->
                saveEdits(replaceOriginal = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveEdits(replaceOriginal: Boolean) {
        if (currentBitmap == null) return
        showToast("Saving edits...")
        processSaveCroppedBitmap(currentBitmap!!, replaceOriginal)
    }
    
    private fun processSaveCroppedBitmap(bitmap: android.graphics.Bitmap, replaceOriginal: Boolean) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val entry = getCurrentEntry() ?: return@launch
            val isVaultFile = entry.relativePath.contains("app_PrivateVault")
            val originalFile = if (isVaultFile) {
                File(entry.relativePath)
            } else {
                File(Environment.getExternalStorageDirectory(), entry.relativePath)
            }
            
            try {
                if (replaceOriginal && originalFile.exists()) {
                    if (isVaultFile) {
                        val tempFile = File(cacheDir, "temp_edit_${System.currentTimeMillis()}.jpg")
                        tempFile.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        com.sk.gallery.data.crypto.CryptoManager.encryptFileLocal(tempFile, originalFile)
                        tempFile.delete()
                    } else {
                        originalFile.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                    }
                } else {
                    val dir = originalFile.parentFile
                    val newFileName = "EDIT_${System.currentTimeMillis()}.jpg"
                    val newFile = File(dir, newFileName)
                    if (isVaultFile) {
                        val tempFile = File(cacheDir, "temp_edit_${System.currentTimeMillis()}.jpg")
                        tempFile.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        com.sk.gallery.data.crypto.CryptoManager.encryptFileLocal(tempFile, newFile)
                        tempFile.delete()
                    } else {
                        newFile.outputStream().use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                        // Trigger media scanner for new file
                        android.media.MediaScannerConnection.scanFile(
                            this@PhotoViewerActivity,
                            arrayOf(newFile.absolutePath),
                            arrayOf("image/jpeg"),
                            null
                        )
                    }
                }
                
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    showToast("Saved successfully!")
                    hasUnsavedChanges = false
                    exitEditMode()
                    // If replaced, update MediaScanner and force Glide cache invalidation
                    if (replaceOriginal) {
                        originalFile.setLastModified(System.currentTimeMillis())
                        android.media.MediaScannerConnection.scanFile(
                            this@PhotoViewerActivity,
                            arrayOf(originalFile.absolutePath),
                            null, null
                        )
                        adapter?.notifyItemChanged(currentIndex)
                    }
                }
            } catch (e: Exception) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    showToast("Failed to save: ${e.message}")
                }
            }
        }
    }

    private var isSlidingDown = false

    private fun finishWithZoomOut() {
        isSlidingDown = false
        finish()
    }

    private fun finishWithSlideDown() {
        isSlidingDown = true
        finish()
    }

    override fun finish() {
        super.finish()
        if (isSlidingDown) {
            overridePendingTransition(R.anim.hold, R.anim.slide_down_out)
        } else {
            overridePendingTransition(R.anim.hold, R.anim.zoom_out)
        }
    }

    private var isLaunchingPicker = false

    override fun onStop() {
        super.onStop()
        val isPrivateMode = intent.getBooleanExtra("EXTRA_IS_PRIVATE", false)
        if (isPrivateMode && !isChangingConfigurations && !isLaunchingPicker && !isFinishing) {
            finishAffinity()
        }
    }

    inner class PhotoPagerAdapter(private val items: List<FileEntry>) :
        RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
            val binding = ItemPhotoPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return PhotoViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun onViewDetachedFromWindow(holder: PhotoViewHolder) {
            super.onViewDetachedFromWindow(holder)
            holder.pauseVideo()
            holder.releasePlayer()
        }

        override fun getItemCount(): Int = items.size

        inner class PhotoViewHolder(private val itemBinding: ItemPhotoPageBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            private var updateProgressRunnable: Runnable? = null
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private var isVideoPlaying = false
            
            // Zooming State Variables
            private var scaleFactor = 1.0f
            private var dx = 0f
            private var dy = 0f
            private var lastTouchX = 0f
            private var lastTouchY = 0f
            private var activePointerId = MotionEvent.INVALID_POINTER_ID

            private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null

            fun pauseVideo() {
                exoPlayer?.pause()
                isVideoPlaying = false
                updateVideoUIState()
                handler.removeCallbacksAndMessages(null)
            }
            
            fun releasePlayer() {
                exoPlayer?.release()
                exoPlayer = null
            }

            fun bind(entry: FileEntry) {
                val file = File(Environment.getExternalStorageDirectory(), entry.relativePath)
                val isVideo = entry.mimeType.startsWith("video", ignoreCase = true)

                if (isVideo) {
                    itemBinding.ivFullPhoto.visibility = View.GONE
                    itemBinding.videoContainer.visibility = View.VISIBLE
                    
                    if (exoPlayer == null) {
                        exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this@PhotoViewerActivity).build()
                        itemBinding.videoView.player = exoPlayer
                    }
                    
                    val isVaultFile = entry.relativePath.contains("app_PrivateVault")
                    val actualFile = if (file.exists()) file else File(entry.relativePath)
                    
                    if (isVaultFile) {
                        val raf = java.io.RandomAccessFile(actualFile, "r")
                        val magic = ByteArray(4)
                        raf.readFully(magic)
                        raf.close()
                        val isV2 = magic.contentEquals(byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte()))
                        
                        if (isV2) {
                            itemBinding.pbVideoLoading.visibility = View.GONE
                            itemBinding.ivCenterPlay.visibility = View.VISIBLE
                            
                            val dataSourceFactory = androidx.media3.datasource.DataSource.Factory {
                                val fileDataSource = androidx.media3.datasource.FileDataSource()
                                com.sk.gallery.util.AesCtrDataSource(
                                    fileDataSource,
                                    com.sk.gallery.data.crypto.CryptoManager.getV2SecretKeyRaw(),
                                    actualFile
                                )
                            }
                            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                                .createMediaSource(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(actualFile)))
                            
                            exoPlayer?.setMediaSource(mediaSource)
                            exoPlayer?.prepare()
                            setupVideoListeners()
                        } else {
                            itemBinding.pbVideoLoading.visibility = View.VISIBLE
                            itemBinding.ivCenterPlay.visibility = View.GONE
                            
                            // Show progress text if not exists, create dynamically if needed
                            var tvProgress = itemBinding.videoContainer.findViewById<android.widget.TextView>(android.view.View.generateViewId())
                            if (tvProgress == null) {
                                tvProgress = android.widget.TextView(this@PhotoViewerActivity).apply {
                                    text = "Decrypting 0%..."
                                    setTextColor(android.graphics.Color.WHITE)
                                    textSize = 14f
                                    // Center below progress bar
                                    val params = android.widget.RelativeLayout.LayoutParams(
                                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                                        android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    params.addRule(android.widget.RelativeLayout.BELOW, itemBinding.pbVideoLoading.id)
                                    params.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                                    params.topMargin = 16
                                    layoutParams = params
                                }
                                // Add to video container, assuming it's a relative layout
                                (itemBinding.videoContainer as? android.view.ViewGroup)?.addView(tvProgress)
                            } else {
                                tvProgress.visibility = View.VISIBLE
                                tvProgress.text = "Decrypting 0%..."
                            }
                            
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val tempVideoFile = File(cacheDir, "temp_vault_video.mp4")
                                try {
                                    com.sk.gallery.data.crypto.CryptoManager.decryptFileLocal(
                                        actualFile, 
                                        tempVideoFile,
                                        onProgress = { percent ->
                                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                                tvProgress.text = "Decrypting $percent%..."
                                            }
                                        },
                                        cancelSignal = { !isActive }
                                    )
                                    
                                    if (isActive) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            itemBinding.pbVideoLoading.visibility = View.GONE
                                            tvProgress.visibility = View.GONE
                                            itemBinding.ivCenterPlay.visibility = View.VISIBLE
                                            exoPlayer?.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(tempVideoFile)))
                                            exoPlayer?.prepare()
                                            setupVideoListeners() 
                                        }
                                    } else {
                                        if (tempVideoFile.exists()) tempVideoFile.delete()
                                    }
                                } catch (e: Exception) {
                                    if (isActive) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            itemBinding.pbVideoLoading.visibility = View.GONE
                                            tvProgress.visibility = View.GONE
                                            showToast("Failed to decrypt video")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        exoPlayer?.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(actualFile)))
                        exoPlayer?.prepare()
                        setupVideoListeners()
                    }

                    // Setup controls
                    val togglePlay = {
                        exoPlayer?.let { player ->
                            if (player.isPlaying) {
                                pauseVideo()
                            } else {
                                player.play()
                                isVideoPlaying = true
                                updateVideoUIState()
                                startProgressUpdater()
                            }
                        }
                    }

                    itemBinding.ivCenterPlay.setOnClickListener { togglePlay() }
                    itemBinding.btnPlayPause.setOnClickListener { togglePlay() }

                    itemBinding.seekBarVideo.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                exoPlayer?.seekTo(progress.toLong())
                                itemBinding.tvVideoTime.text = "${com.sk.gallery.util.FileUtils.formatDuration(progress.toLong())} / ${com.sk.gallery.util.FileUtils.formatDuration(exoPlayer?.duration ?: 0L)}"
                            }
                        }
                        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    })
                } else {
                    itemBinding.ivFullPhoto.visibility = View.VISIBLE
                    itemBinding.videoContainer.visibility = View.GONE
                    
                    val fullModel = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(entry, false)
                    val thumbModel = com.sk.gallery.util.MediaLoaderHelper.getGlideModel(entry, true)
                    Glide.with(this@PhotoViewerActivity)
                        .load(fullModel)
                        .thumbnail(Glide.with(this@PhotoViewerActivity).load(thumbModel))
                        .placeholder(android.R.color.black)
                        .into(itemBinding.ivFullPhoto)
                }

                val scaleGestureDetector = android.view.ScaleGestureDetector(this@PhotoViewerActivity, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                        scaleFactor *= detector.scaleFactor
                        scaleFactor = Math.max(1.0f, Math.min(scaleFactor, 5.0f))
                        itemBinding.mediaWrapper.scaleX = scaleFactor
                        itemBinding.mediaWrapper.scaleY = scaleFactor
                        binding.viewerViewPager.isUserInputEnabled = scaleFactor <= 1.0f
                        return true
                    }
                })

                val gestureDetector = GestureDetector(this@PhotoViewerActivity, object : GestureDetector.SimpleOnGestureListener() {
                    private val swipeThreshold = 100
                    private val swipeVelocityThreshold = 100

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        toggleBarsVisibility()
                        
                        // Toggle video controls visibility if it's a video
                        if (isVideo) {
                            if (areBarsVisible) {
                                itemBinding.llVideoControls.visibility = View.VISIBLE
                                updateVideoUIState() // Show center play if paused
                            } else {
                                itemBinding.llVideoControls.visibility = View.GONE
                                itemBinding.ivCenterPlay.visibility = View.GONE
                            }
                        }
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (scaleFactor > 1.0f) {
                            scaleFactor = 1.0f
                            dx = 0f
                            dy = 0f
                        } else {
                            scaleFactor = 2.5f
                        }
                        itemBinding.mediaWrapper.animate()
                            .scaleX(scaleFactor)
                            .scaleY(scaleFactor)
                            .translationX(dx)
                            .translationY(dy)
                            .setDuration(200)
                            .start()
                        binding.viewerViewPager.isUserInputEnabled = scaleFactor <= 1.0f
                        return true
                    }

                    override fun onFling(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        velocityX: Float,
                        velocityY: Float
                    ): Boolean {
                        if (e1 == null) return false
                        if (scaleFactor > 1.0f) return false // Disable fling actions when zoomed
                        
                        val diffY = e2.y - e1.y
                        val diffX = e2.x - e1.x

                        if (Math.abs(diffY) > Math.abs(diffX)) {
                            if (Math.abs(diffY) > swipeThreshold && Math.abs(velocityY) > swipeVelocityThreshold) {
                                if (diffY < 0) {
                                    showAboutBottomSheet()
                                    return true
                                } else {
                                    finishWithSlideDown()
                                    return true
                                }
                            }
                        }
                        return false
                    }
                })

                // Put touch listener on the root frame layout so it catches gestures for both
                itemBinding.root.setOnTouchListener { view, event ->
                    if (event.pointerCount > 1 || scaleFactor > 1.0f) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    
                    scaleGestureDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)
                    
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lastTouchX = event.x
                            lastTouchY = event.y
                            activePointerId = event.getPointerId(0)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val pointerIndex = event.findPointerIndex(activePointerId)
                            if (pointerIndex != -1) {
                                val x = event.getX(pointerIndex)
                                val y = event.getY(pointerIndex)
                                if (!scaleGestureDetector.isInProgress && scaleFactor > 1.0f) {
                                    val dxMove = x - lastTouchX
                                    val dyMove = y - lastTouchY
                                    dx += dxMove
                                    dy += dyMove
                                    
                                    val maxDx = (itemBinding.mediaWrapper.width * (scaleFactor - 1)) / 2f
                                    val maxDy = (itemBinding.mediaWrapper.height * (scaleFactor - 1)) / 2f
                                    
                                    dx = Math.max(-maxDx, Math.min(dx, maxDx))
                                    dy = Math.max(-maxDy, Math.min(dy, maxDy))
                                    
                                    itemBinding.mediaWrapper.translationX = dx
                                    itemBinding.mediaWrapper.translationY = dy
                                }
                                lastTouchX = x
                                lastTouchY = y
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            // Snap back if scale is near 1
                            if (scaleFactor <= 1.05f) {
                                scaleFactor = 1.0f
                                dx = 0f
                                dy = 0f
                                itemBinding.mediaWrapper.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .translationX(0f).translationY(0f)
                                    .setDuration(150).start()
                                binding.viewerViewPager.isUserInputEnabled = true
                            }
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            val pointerIndex = event.actionIndex
                            val pointerId = event.getPointerId(pointerIndex)
                            if (pointerId == activePointerId) {
                                val newPointerIndex = if (pointerIndex == 0) 1 else 0
                                lastTouchX = event.getX(newPointerIndex)
                                lastTouchY = event.getY(newPointerIndex)
                                activePointerId = event.getPointerId(newPointerIndex)
                            }
                        }
                    }
                    true
                }
                
                // Sync initial bars state
                if (isVideo) {
                    itemBinding.llVideoControls.visibility = if (areBarsVisible) View.VISIBLE else View.GONE
                    updateVideoUIState()
                }
            }
            
            private fun setupVideoListeners() {
                exoPlayer?.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_READY) {
                            itemBinding.seekBarVideo.max = exoPlayer?.duration?.toInt() ?: 0
                            val dur = exoPlayer?.duration ?: 0L
                            itemBinding.tvVideoTime.text = "00:00 / ${com.sk.gallery.util.FileUtils.formatDuration(dur)}"
                            // We don't auto-seek to 1 for ExoPlayer since it renders the first frame automatically
                        } else if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                            exoPlayer?.seekTo(0)
                            pauseVideo()
                            itemBinding.seekBarVideo.progress = 0
                            val dur = exoPlayer?.duration ?: 0L
                            itemBinding.tvVideoTime.text = "00:00 / ${com.sk.gallery.util.FileUtils.formatDuration(dur)}"
                        }
                    }
                })
            }
            
            private fun updateVideoUIState() {
                if (isVideoPlaying) {
                    itemBinding.btnPlayPause.setImageResource(R.drawable.ic_pause)
                    itemBinding.ivCenterPlay.visibility = View.GONE
                } else {
                    itemBinding.btnPlayPause.setImageResource(R.drawable.ic_play)
                    if (areBarsVisible) {
                        itemBinding.ivCenterPlay.visibility = View.VISIBLE
                    }
                }
            }

            private fun startProgressUpdater() {
                updateProgressRunnable = object : Runnable {
                    override fun run() {
                        val player = exoPlayer ?: return
                        if (player.isPlaying) {
                            val currentPos = player.currentPosition.toInt()
                            val duration = player.duration.toInt()
                            
                            if (duration > 0) {
                                itemBinding.seekBarVideo.max = duration
                                itemBinding.seekBarVideo.progress = currentPos
                                itemBinding.tvVideoTime.text = "${com.sk.gallery.util.FileUtils.formatDuration(currentPos.toLong())} / ${com.sk.gallery.util.FileUtils.formatDuration(duration.toLong())}"
                            }
                            handler.postDelayed(this, 100)
                        }
                    }
                }
                handler.post(updateProgressRunnable!!)
            }
        }
    }

    class FilterAdapter(
        private val context: android.content.Context,
        private val thumbnail: android.graphics.Bitmap?,
        private val presets: List<FilterPreset>,
        private var selectedId: String,
        private val onSelect: (FilterPreset) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<FilterAdapter.ViewHolder>() {

        inner class ViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val thumbnailImg = view.findViewById<android.widget.ImageView>(R.id.img_filter_thumbnail)
            val borderView = view.findViewById<android.view.View>(R.id.view_filter_border)
            val nameTv = view.findViewById<android.widget.TextView>(R.id.tv_filter_name)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val v = android.view.LayoutInflater.from(context).inflate(R.layout.item_adjust_filter, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val preset = presets[position]
            holder.nameTv.text = preset.name
            
            if (thumbnail != null) {
                val bmp = android.graphics.Bitmap.createBitmap(thumbnail.width, thumbnail.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                val paint = android.graphics.Paint().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(preset.matrix)
                }
                canvas.drawBitmap(thumbnail, 0f, 0f, paint)
                holder.thumbnailImg.setImageBitmap(bmp)
            }
            
            if (preset.id == selectedId) {
                holder.borderView.visibility = android.view.View.VISIBLE
            } else {
                holder.borderView.visibility = android.view.View.GONE
            }

            holder.itemView.setOnClickListener {
                if (selectedId != preset.id) {
                    val oldSelected = selectedId
                    selectedId = preset.id
                    
                    val oldPos = presets.indexOfFirst { it.id == oldSelected }
                    if (oldPos != -1) notifyItemChanged(oldPos)
                    notifyItemChanged(position)
                    
                    onSelect(preset)
                }
            }
        }

        override fun getItemCount() = presets.size
    }
}
