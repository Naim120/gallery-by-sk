package com.sk.gallery.ui.collage

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.constraintlayout.widget.ConstraintLayout
import com.sk.gallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class CollageActivity : AppCompatActivity() {

    private lateinit var collageMain: CollageView
    private lateinit var rvTemplates: RecyclerView
    private lateinit var btnSave: Button
    private lateinit var overlayLoading: FrameLayout
    
    private lateinit var tabRatios: TextView
    private lateinit var tabTemplates: TextView
    private lateinit var tabBorders: TextView
    private lateinit var tabColors: TextView
    
    private lateinit var rvRatios: RecyclerView
    private lateinit var layoutBorders: LinearLayout
    private lateinit var rvColors: RecyclerView
    private lateinit var seekSpacing: SeekBar
    private lateinit var seekCorners: SeekBar

    private var imageUris: List<Uri> = emptyList()
    private var templates: List<CollageTemplate> = emptyList()
    
    private var currentRatioValue: Float = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collage)

        // Setup Window Flags for full screen/edge-to-edge if needed
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_back_modern) // Assume ic_back exists
        toolbar.setNavigationOnClickListener { finish() }

        collageMain = findViewById(R.id.collage_main)
        rvTemplates = findViewById(R.id.rv_templates)
        btnSave = findViewById(R.id.btn_save)
        overlayLoading = findViewById(R.id.overlay_loading)
        
        tabRatios = findViewById(R.id.tab_ratios)
        tabTemplates = findViewById(R.id.tab_templates)
        tabBorders = findViewById(R.id.tab_borders)
        tabColors = findViewById(R.id.tab_colors)
        
        rvRatios = findViewById(R.id.rv_ratios)
        layoutBorders = findViewById(R.id.layout_borders)
        rvColors = findViewById(R.id.rv_colors)
        seekSpacing = findViewById(R.id.seek_spacing)
        seekCorners = findViewById(R.id.seek_corners)

        val uris = intent.getParcelableArrayListExtra<Uri>("extra_uris")
        if (uris == null || uris.isEmpty()) {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        imageUris = uris

        // Load templates based on image count
        templates = CollageTemplate.getTemplatesForCount(imageUris.size)
        if (templates.isEmpty()) {
            Toast.makeText(this, "No templates available for this many images.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup Main Collage
        collageMain.setImages(imageUris)
        collageMain.setTemplate(templates[0])

        // Setup RecyclerView
        rvTemplates.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val adapter = CollageTemplateAdapter(templates, imageUris) { selectedTemplate ->
            collageMain.setTemplate(selectedTemplate)
        }
        rvTemplates.adapter = adapter
        
        // Setup Tabs
        val unselectedColor = Color.parseColor("#888888")
        val selectedColor = Color.parseColor("#0891B2")

        fun selectTab(selectedTab: TextView, selectedView: View) {
            tabRatios.setTextColor(unselectedColor)
            tabTemplates.setTextColor(unselectedColor)
            tabBorders.setTextColor(unselectedColor)
            tabColors.setTextColor(unselectedColor)
            
            tabRatios.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabTemplates.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabBorders.setTypeface(null, android.graphics.Typeface.NORMAL)
            tabColors.setTypeface(null, android.graphics.Typeface.NORMAL)
            
            selectedTab.setTextColor(selectedColor)
            selectedTab.setTypeface(null, android.graphics.Typeface.BOLD)
            
            rvRatios.visibility = View.GONE
            rvTemplates.visibility = View.GONE
            layoutBorders.visibility = View.GONE
            rvColors.visibility = View.GONE
            
            selectedView.visibility = View.VISIBLE
        }

        tabRatios.setOnClickListener { selectTab(tabRatios, rvRatios) }
        tabTemplates.setOnClickListener { selectTab(tabTemplates, rvTemplates) }
        tabBorders.setOnClickListener { selectTab(tabBorders, layoutBorders) }
        tabColors.setOnClickListener { selectTab(tabColors, rvColors) }
        
        // Setup Sliders
        seekSpacing.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                collageMain.spacing = progress.toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekCorners.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                collageMain.cornerRadius = progress.toFloat()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Setup Colors
        val colors = listOf(
            Color.WHITE,
            Color.BLACK,
            Color.parseColor("#F5F5F5"), // Light Gray
            Color.parseColor("#151821"), // Dark Gray
            Color.parseColor("#FFD1DC"), // Pastel Pink
            Color.parseColor("#AEC6CF"), // Pastel Blue
            Color.parseColor("#77DD77"), // Pastel Green
            Color.parseColor("#FDFD96"), // Pastel Yellow
            Color.parseColor("#CBAACB")  // Pastel Purple
        )
        
        rvColors.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvColors.adapter = ColorAdapter(colors) { color ->
            collageMain.collageBgColor = color
            (collageMain.parent as? androidx.cardview.widget.CardView)?.setCardBackgroundColor(color)
        }

        // Setup Ratios
        val ratioList = listOf(
            AspectRatioItem("1:1", "1:1", 1f),
            AspectRatioItem("4:5", "4:5", 4f / 5f),
            AspectRatioItem("16:9", "16:9", 16f / 9f),
            AspectRatioItem("9:16", "9:16", 9f / 16f),
            AspectRatioItem("3:4", "3:4", 3f / 4f),
            AspectRatioItem("4:3", "4:3", 4f / 3f)
        )
        
        rvRatios.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvRatios.adapter = RatioAdapter(ratioList) { selectedRatio ->
            currentRatioValue = selectedRatio.ratioValue
            val cardView = collageMain.parent as? androidx.cardview.widget.CardView
            if (cardView != null) {
                val params = cardView.layoutParams as? ConstraintLayout.LayoutParams
                if (params != null) {
                    params.dimensionRatio = selectedRatio.ratioString
                    cardView.layoutParams = params
                }
            }
            collageMain.invalidate()
        }

        // Setup Save
        btnSave.setOnClickListener {
            saveCollage()
        }
    }

    private fun saveCollage() {
        overlayLoading.visibility = View.VISIBLE
        btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                // Generate high res bitmap on background thread
                val bitmap = collageMain.generateHighResBitmap(2048, currentRatioValue)
                
                // Save to MediaStore
                val savedUri = saveBitmapToMediaStore(bitmap)
                
                withContext(Dispatchers.Main) {
                    overlayLoading.visibility = View.GONE
                    btnSave.isEnabled = true
                    if (savedUri != null) {
                        Toast.makeText(this@CollageActivity, "Collage saved!", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this@CollageActivity, "Failed to save collage.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    overlayLoading.visibility = View.GONE
                    btnSave.isEnabled = true
                    Toast.makeText(this@CollageActivity, "Error saving collage.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun saveBitmapToMediaStore(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val filename = "Collage_${System.currentTimeMillis()}.jpg"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Collages")
        }

        val resolver = contentResolver
        var uri: Uri? = null
        
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (uri != null) {
                resolver.delete(uri, null, null)
                uri = null
            }
        }
        
        uri
    }
}
