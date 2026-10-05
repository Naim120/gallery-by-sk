package com.sk.gallery.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.media.MediaScannerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

object PdfGenerator {

    // Standard A4 dimensions in PostScript points (72 DPI)
    private const val A4_WIDTH = 595
    private const val A4_HEIGHT = 842
    private const val PAGE_MARGIN = 20f

    // Max decode resolution for each image to keep memory consumption low while retaining crisp print quality
    private const val MAX_DECODE_DIMENSION = 2480

    suspend fun generatePdf(
        context: Context,
        pages: List<PdfPageModel>,
        outputFile: File,
        onProgress: (current: Int, total: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext false

        val pdfDocument = PdfDocument()

        try {
            outputFile.parentFile?.let {
                if (!it.exists()) it.mkdirs()
            }

            for ((index, pageModel) in pages.withIndex()) {
                coroutineContext.ensureActive()

                val file = File(pageModel.filePath)
                if (!file.exists()) continue

                // 1. Determine image dimensions and EXIF orientation
                val exifRotation = getExifRotation(file.absolutePath)
                val totalRotation = (exifRotation + pageModel.rotationDegrees) % 360

                // 2. Decode image with safe downsampling
                val bitmap = decodeSampledBitmap(file.absolutePath, MAX_DECODE_DIMENSION) ?: continue
                
                // 3. Apply rotation if needed
                val finalBitmap = if (totalRotation != 0) {
                    val matrix = Matrix().apply { postRotate(totalRotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) {
                        bitmap.recycle()
                    }
                    rotated
                } else {
                    bitmap
                }

                // 4. Determine page dimensions based on image aspect ratio
                val isLandscape = finalBitmap.width > finalBitmap.height
                val pageWidth = if (isLandscape) A4_HEIGHT else A4_WIDTH
                val pageHeight = if (isLandscape) A4_WIDTH else A4_HEIGHT

                // 5. Create PDF page
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // 6. Draw bitmap centered within printable area
                val printableWidth = pageWidth - (2 * PAGE_MARGIN)
                val printableHeight = pageHeight - (2 * PAGE_MARGIN)

                val scale = minOf(
                    printableWidth / finalBitmap.width.toFloat(),
                    printableHeight / finalBitmap.height.toFloat()
                )

                val drawWidth = finalBitmap.width * scale
                val drawHeight = finalBitmap.height * scale
                val left = PAGE_MARGIN + (printableWidth - drawWidth) / 2f
                val top = PAGE_MARGIN + (printableHeight - drawHeight) / 2f

                val destRect = RectF(left, top, left + drawWidth, top + drawHeight)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(finalBitmap, null, destRect, paint)

                pdfDocument.finishPage(page)
                finalBitmap.recycle()

                withContext(Dispatchers.Main) {
                    onProgress(index + 1, pages.size)
                }
            }

            // 7. Write to output file
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }

            // 8. Notify MediaScanner so the PDF is immediately visible in file explorers
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(outputFile.absolutePath),
                arrayOf("application/pdf"),
                null
            )

            true
        } catch (e: Exception) {
            e.printStackTrace()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            false
        } finally {
            try {
                pdfDocument.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getExifRotation(filePath: String): Int {
        return try {
            val exif = ExifInterface(filePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun decodeSampledBitmap(filePath: String, maxDimension: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        val origWidth = options.outWidth
        val origHeight = options.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        var inSampleSize = 1
        var halfWidth = origWidth
        var halfHeight = origHeight

        while (halfWidth > maxDimension || halfHeight > maxDimension) {
            inSampleSize *= 2
            halfWidth /= 2
            halfHeight /= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return try {
            BitmapFactory.decodeFile(filePath, decodeOptions)
        } catch (e: OutOfMemoryError) {
            // Fallback with higher inSampleSize on low memory
            decodeOptions.inSampleSize = inSampleSize * 2
            try {
                BitmapFactory.decodeFile(filePath, decodeOptions)
            } catch (e2: Exception) {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
