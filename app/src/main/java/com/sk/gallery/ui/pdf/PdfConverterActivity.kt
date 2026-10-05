package com.sk.gallery.ui.pdf

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.Rotate
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sk.gallery.databinding.ActivityPdfConverterBinding
import com.sk.gallery.databinding.DialogPdfSuccessBinding
import kotlinx.coroutines.launch
import java.io.File

class PdfConverterActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        const val EXTRA_DEFAULT_NAME = "extra_default_name"
        const val EXTRA_PDF_PATH = "extra_pdf_path"
        const val EXTRA_PDF_NAME = "extra_pdf_name"
    }

    private lateinit var binding: ActivityPdfConverterBinding
    private lateinit var adapter: PdfPageAdapter
    private var isConverting = false

    private var currentPreviewIndex = -1
    private var previewScaleFactor = 1.0f
    private var previewDx = 0f
    private var previewDy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfConverterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS) ?: arrayListOf()
        val defaultName = intent.getStringExtra(EXTRA_DEFAULT_NAME)

        if (imagePaths.isEmpty()) {
            Toast.makeText(this, "No images selected for PDF", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupFileNameInput(defaultName, imagePaths.firstOrNull())
        setupRecyclerView(imagePaths)
        setupPreviewControls()
        setupActions()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (!isConverting) {
                finish()
            }
        }
    }

    private fun setupFileNameInput(defaultName: String?, firstPath: String?) {
        val suggestedName = when {
            !defaultName.isNullOrBlank() -> defaultName
            !firstPath.isNullOrBlank() -> File(firstPath).nameWithoutExtension
            else -> "Document_${System.currentTimeMillis()}"
        }.replace(Regex("[/\\\\:*?\"<>|]"), "_")

        binding.etPdfName.setText(suggestedName)
    }

    private fun setupRecyclerView(imagePaths: List<String>) {
        val pages = imagePaths.map { PdfPageModel(it) }.toMutableList()

        adapter = PdfPageAdapter(
            pages = pages,
            onPageClick = { position ->
                showPreview(position)
            },
            onPageCountChanged = { remainingCount ->
                updatePageCount(remainingCount)
            }
        )

        val gridLayoutManager = GridLayoutManager(this, 3)
        binding.rvPdfPages.layoutManager = gridLayoutManager
        binding.rvPdfPages.adapter = adapter

        // Setup Drag-to-Reorder
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    adapter.moveItem(from, to)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = true
        })

        itemTouchHelper.attachToRecyclerView(binding.rvPdfPages)
        updatePageCount(pages.size)
    }

    private fun updatePageCount(count: Int) {
        binding.tvPageCount.text = "$count ${if (count == 1) "page" else "pages"}"
        if (count == 0) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.btnConvert.isEnabled = false
            binding.btnConvert.alpha = 0.5f
        } else {
            binding.tvEmptyState.visibility = View.GONE
            binding.btnConvert.isEnabled = true
            binding.btnConvert.alpha = 1.0f
        }
    }

    private fun setupActions() {
        binding.btnConvert.setOnClickListener {
            if (isConverting) return@setOnClickListener

            val currentPages = adapter.pages
            if (currentPages.isEmpty()) {
                Toast.makeText(this, "Please keep at least one page", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var baseName = binding.etPdfName.text.toString().trim()
            if (baseName.isEmpty()) {
                baseName = "Document_${System.currentTimeMillis()}"
            }
            baseName = baseName.replace(Regex("[/\\\\:*?\"<>|]"), "_")

            startConversion(baseName, currentPages.toList())
        }
    }

    private fun startConversion(baseName: String, pages: List<PdfPageModel>) {
        isConverting = true
        binding.overlayLoading.visibility = View.VISIBLE
        binding.tvConvertingStatus.text = "Converting 1 of ${pages.size}..."

        lifecycleScope.launch {
            try {
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val targetDir = File(documentsDir, "GalleryPDF")
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                var targetFile = File(targetDir, "$baseName.pdf")
                var counter = 1
                while (targetFile.exists()) {
                    targetFile = File(targetDir, "${baseName}_$counter.pdf")
                    counter++
                }

                val success = PdfGenerator.generatePdf(
                    context = this@PdfConverterActivity,
                    pages = pages,
                    outputFile = targetFile
                ) { current, total ->
                    binding.tvConvertingStatus.text = "Converting $current of $total..."
                }

                binding.overlayLoading.visibility = View.GONE
                isConverting = false

                if (success && targetFile.exists()) {
                    // Set result for caller activity/fragment
                    val resultIntent = Intent().apply {
                        putExtra(EXTRA_PDF_PATH, targetFile.absolutePath)
                        putExtra(EXTRA_PDF_NAME, targetFile.name)
                    }
                    setResult(RESULT_OK, resultIntent)

                    showSuccessDialog(targetFile)
                } else {
                    Toast.makeText(this@PdfConverterActivity, "Failed to create PDF", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.overlayLoading.visibility = View.GONE
                isConverting = false
                Toast.makeText(this@PdfConverterActivity, "Error creating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showSuccessDialog(pdfFile: File) {
        val dialog = BottomSheetDialog(this)
        val dialogBinding = DialogPdfSuccessBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(false)

        dialogBinding.tvSuccessFilename.text = pdfFile.name
        dialogBinding.tvSuccessPath.text = "Saved in Documents/GalleryPDF"

        dialogBinding.btnOpenPdf.setOnClickListener {
            dialog.dismiss()
            openPdf(pdfFile)
            finish()
        }

        dialogBinding.btnDone.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private fun openPdf(pdfFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                pdfFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open PDF with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPreviewControls() {
        binding.btnPreviewClose.setOnClickListener { closePreview() }
        binding.btnPreviewPrev.setOnClickListener {
            if (currentPreviewIndex > 0) {
                showPreview(currentPreviewIndex - 1)
            }
        }
        binding.btnPreviewNext.setOnClickListener {
            if (currentPreviewIndex < adapter.pages.size - 1) {
                showPreview(currentPreviewIndex + 1)
            }
        }
        binding.btnPreviewRotate.setOnClickListener {
            rotateCurrentPreviewPage()
        }
        binding.btnPreviewDelete.setOnClickListener {
            deleteCurrentPreviewPage()
        }

        setupPreviewGestures()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val s = detector.scaleFactor
                val w = binding.previewMediaWrapper.width.toFloat()
                val h = binding.previewMediaWrapper.height.toFloat()

                var newScale = previewScaleFactor * s
                newScale = Math.max(1.0f, Math.min(newScale, 5.0f))

                val actualS = newScale / previewScaleFactor
                previewDx = (detector.focusX - w / 2f) * (1 - actualS) + actualS * previewDx
                previewDy = (detector.focusY - h / 2f) * (1 - actualS) + actualS * previewDy
                previewScaleFactor = newScale

                val maxDx = (w * (previewScaleFactor - 1)) / 2f
                val maxDy = (h * (previewScaleFactor - 1)) / 2f
                previewDx = Math.max(-maxDx, Math.min(previewDx, maxDx))
                previewDy = Math.max(-maxDy, Math.min(previewDy, maxDy))

                binding.previewMediaWrapper.scaleX = previewScaleFactor
                binding.previewMediaWrapper.scaleY = previewScaleFactor
                binding.previewMediaWrapper.translationX = previewDx
                binding.previewMediaWrapper.translationY = previewDy

                updateNavigationButtonsVisibility()
                return true
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (previewScaleFactor > 1.05f) {
                    resetZoom()
                } else {
                    previewScaleFactor = 2.5f
                    previewDx = 0f
                    previewDy = 0f
                    binding.previewMediaWrapper.animate()
                        .scaleX(previewScaleFactor)
                        .scaleY(previewScaleFactor)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(200)
                        .start()
                }
                updateNavigationButtonsVisibility()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || previewScaleFactor > 1.05f) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    if (diffX > 0 && currentPreviewIndex > 0) {
                        showPreview(currentPreviewIndex - 1)
                        return true
                    } else if (diffX < 0 && currentPreviewIndex < adapter.pages.size - 1) {
                        showPreview(currentPreviewIndex + 1)
                        return true
                    }
                }
                return false
            }
        })

        var lastTouchX = 0f
        var lastTouchY = 0f

        binding.previewImageContainer.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (previewScaleFactor > 1.05f && !scaleDetector.isInProgress) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY

                        val w = binding.previewMediaWrapper.width.toFloat()
                        val h = binding.previewMediaWrapper.height.toFloat()
                        val maxDx = (w * (previewScaleFactor - 1)) / 2f
                        val maxDy = (h * (previewScaleFactor - 1)) / 2f

                        previewDx = Math.max(-maxDx, Math.min(previewDx + dx, maxDx))
                        previewDy = Math.max(-maxDy, Math.min(previewDy + dy, maxDy))

                        binding.previewMediaWrapper.translationX = previewDx
                        binding.previewMediaWrapper.translationY = previewDy
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            true
        }
    }

    private fun showPreview(position: Int) {
        if (position !in 0 until adapter.pages.size) return
        currentPreviewIndex = position
        val page = adapter.pages[position]

        resetZoom()
        renderPreviewImage(page)

        binding.tvPreviewPageIndex.text = "Page ${position + 1} of ${adapter.pages.size}"
        binding.tvPreviewFilename.text = File(page.filePath).name
        binding.overlayPreview.visibility = View.VISIBLE

        updateNavigationButtonsVisibility()
    }

    private fun renderPreviewImage(page: PdfPageModel) {
        val file = File(page.filePath)
        var request = Glide.with(this)
            .load(file)
            .fitCenter()

        if (page.rotationDegrees != 0) {
            request = request.transform(Rotate(page.rotationDegrees))
        }
        request.into(binding.ivPreviewImage)
    }

    private fun updateNavigationButtonsVisibility() {
        if (previewScaleFactor > 1.05f) {
            binding.btnPreviewPrev.visibility = View.GONE
            binding.btnPreviewNext.visibility = View.GONE
        } else {
            binding.btnPreviewPrev.visibility = if (currentPreviewIndex > 0) View.VISIBLE else View.GONE
            binding.btnPreviewNext.visibility = if (currentPreviewIndex < adapter.pages.size - 1) View.VISIBLE else View.GONE
        }
    }

    private fun resetZoom() {
        previewScaleFactor = 1.0f
        previewDx = 0f
        previewDy = 0f
        binding.previewMediaWrapper.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(150)
            .start()
    }

    private fun closePreview() {
        binding.overlayPreview.visibility = View.GONE
        currentPreviewIndex = -1
        resetZoom()
    }

    private fun rotateCurrentPreviewPage() {
        if (currentPreviewIndex in 0 until adapter.pages.size) {
            val page = adapter.pages[currentPreviewIndex]
            page.rotationDegrees = (page.rotationDegrees + 90) % 360
            adapter.notifyItemChanged(currentPreviewIndex)
            renderPreviewImage(page)
        }
    }

    private fun deleteCurrentPreviewPage() {
        if (currentPreviewIndex in 0 until adapter.pages.size) {
            val removeIndex = currentPreviewIndex
            adapter.pages.removeAt(removeIndex)
            adapter.notifyItemRemoved(removeIndex)
            adapter.notifyItemRangeChanged(removeIndex, adapter.pages.size - removeIndex)
            updatePageCount(adapter.pages.size)

            if (adapter.pages.isEmpty()) {
                closePreview()
            } else {
                val nextIndex = minOf(removeIndex, adapter.pages.size - 1)
                showPreview(nextIndex)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.overlayPreview.visibility == View.VISIBLE) {
            closePreview()
            return
        }
        if (!isConverting) {
            super.onBackPressed()
        }
    }
}
