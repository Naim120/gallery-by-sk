package com.sk.gallery.ui.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var template: CollageTemplate? = null
    private var imageUris: List<Uri> = emptyList()
    private val bitmaps = mutableMapOf<Uri, Bitmap>()
    private val imageOffsets = mutableMapOf<Uri, android.graphics.PointF>()
    private val imageScales = mutableMapOf<Uri, Float>()
    
    var isInteractive = true
    
    private var activeUri: Uri? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    private var isSwapMode = false
    private var swapHoverUri: Uri? = null
    private var swapHoverX = 0f
    private var swapHoverY = 0f
    
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlop = 20f
    
    private val longPressRunnable = Runnable {
        if (activeUri != null && !isSwapMode) {
            isSwapMode = true
            swapHoverUri = activeUri
            swapHoverX = lastTouchX
            swapHoverY = lastTouchY
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }
    
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f // 5dp border roughly
    }

    private val bgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    var spacing = 10f
        set(value) { field = value; invalidate() }
        
    var cornerRadius = 0f
        set(value) { field = value; invalidate() }
        
    var collageBgColor = Color.WHITE
        set(value) { field = value; invalidate() }
        
    private val scaleGestureDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
            activeUri?.let { uri ->
                if (!isSwapMode) {
                    val currentScale = imageScales[uri] ?: 1f
                    val newScale = (currentScale * detector.scaleFactor).coerceIn(1f, 5f)
                    imageScales[uri] = newScale
                    invalidate()
                    return true
                }
            }
            return false
        }
    })

    fun setTemplate(template: CollageTemplate) {
        this.template = template
        invalidate()
    }

    fun setImages(uris: List<Uri>) {
        this.imageUris = uris
        this.imageOffsets.clear() // Reset offsets for new images
        this.imageScales.clear() // Reset zoom scales
        loadImages()
    }

    private fun loadImages() {
        // Load downscaled images for preview
        imageUris.forEach { uri ->
            Glide.with(context)
                .asBitmap()
                .load(uri)
                .override(1024, 1024) // optimized size without forcing aspect ratio
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(
                        resource: Bitmap,
                        transition: Transition<in Bitmap>?
                    ) {
                        bitmaps[uri] = resource
                        invalidate()
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(collageBgColor)

        val currentTemplate = template ?: return
        if (bitmaps.isEmpty()) return

        val outerMargin = spacing / 2
        val drawWidth = width.toFloat() - spacing
        val drawHeight = height.toFloat() - spacing

        for (i in 0 until minOf(imageUris.size, currentTemplate.bounds.size)) {
            val uri = imageUris[i]
            val bitmap = bitmaps[uri] ?: continue
            val normalizedRect = currentTemplate.bounds[i]

            val left = outerMargin + normalizedRect.left * drawWidth + (if (normalizedRect.left > 0) spacing / 2 else 0f)
            val top = outerMargin + normalizedRect.top * drawHeight + (if (normalizedRect.top > 0) spacing / 2 else 0f)
            val right = outerMargin + normalizedRect.right * drawWidth - (if (normalizedRect.right < 1f) spacing / 2 else 0f)
            val bottom = outerMargin + normalizedRect.bottom * drawHeight - (if (normalizedRect.bottom < 1f) spacing / 2 else 0f)
            
            val targetRect = RectF(left, top, right, bottom)
            
            canvas.save()
            if (cornerRadius > 0f) {
                val path = android.graphics.Path()
                path.addRoundRect(targetRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
                canvas.clipPath(path)
            } else {
                canvas.clipRect(targetRect)
            }
            
            // Draw bitmap with center crop logic + pan offset
            val matrix = getCenterCropMatrix(bitmap, targetRect, uri, false, 1f)
            canvas.drawBitmap(bitmap, matrix, null)
            
            canvas.restore()
        }
        
        // Draw hovering image for drag-and-drop swap
        if (isSwapMode && swapHoverUri != null) {
            val bitmap = bitmaps[swapHoverUri]
            if (bitmap != null) {
                val size = 250f // size of floating thumbnail
                val rect = RectF(swapHoverX - size/2, swapHoverY - size/2, swapHoverX + size/2, swapHoverY + size/2)
                
                canvas.save()
                canvas.clipRect(rect)
                
                val matrix = Matrix()
                val scale = maxOf(rect.width() / bitmap.width, rect.height() / bitmap.height)
                matrix.setScale(scale, scale)
                val dx = (rect.width() - bitmap.width * scale) / 2f
                val dy = (rect.height() - bitmap.height * scale) / 2f
                matrix.postTranslate(rect.left + dx, rect.top + dy)
                
                val alphaPaint = Paint().apply { alpha = 180 }
                canvas.drawBitmap(bitmap, matrix, alphaPaint)
                
                val hoverBorderPaint = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                canvas.drawRect(rect, hoverBorderPaint)
                
                canvas.restore()
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!isInteractive) return super.onTouchEvent(event)
        
        scaleGestureDetector.onTouchEvent(event)

        val x = event.x
        val y = event.y

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                activeUri = null
                isSwapMode = false
                swapHoverUri = null
                val currentTemplate = template ?: return false
                val limit = minOf(imageUris.size, currentTemplate.bounds.size) - 1
                for (i in limit downTo 0) {
                    val normalizedRect = currentTemplate.bounds[i]
                    val left = normalizedRect.left * width + (if (normalizedRect.left > 0) spacing / 2 else 0f)
                    val top = normalizedRect.top * height + (if (normalizedRect.top > 0) spacing / 2 else 0f)
                    val right = normalizedRect.right * width - (if (normalizedRect.right < 1f) spacing / 2 else 0f)
                    val bottom = normalizedRect.bottom * height - (if (normalizedRect.bottom < 1f) spacing / 2 else 0f)
                    val targetRect = RectF(left, top, right, bottom)
                    
                    if (targetRect.contains(x, y)) {
                        activeUri = imageUris[i]
                        lastTouchX = x
                        lastTouchY = y
                        touchDownX = x
                        touchDownY = y
                        postDelayed(longPressRunnable, 400)
                        return true
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                activeUri?.let { uri ->
                    if (isSwapMode) {
                        swapHoverX = x
                        swapHoverY = y
                        invalidate()
                        return true
                    } else {
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        val dist = kotlin.math.hypot(x - touchDownX, y - touchDownY)
                        
                        if (dist > touchSlop) {
                            removeCallbacks(longPressRunnable)
                        }
                        
                        val currentOffset = imageOffsets[uri] ?: android.graphics.PointF(0f, 0f)
                        currentOffset.x += dx
                        currentOffset.y += dy
                        imageOffsets[uri] = currentOffset
                        
                        lastTouchX = x
                        lastTouchY = y
                        invalidate()
                        return true
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (isSwapMode && swapHoverUri != null) {
                    val currentTemplate = template
                    if (currentTemplate != null) {
                        val limit = minOf(imageUris.size, currentTemplate.bounds.size) - 1
                        var targetUri: Uri? = null
                        var targetIndex = -1
                        for (i in limit downTo 0) {
                            val normalizedRect = currentTemplate.bounds[i]
                            val left = normalizedRect.left * width + (if (normalizedRect.left > 0) spacing / 2 else 0f)
                            val top = normalizedRect.top * height + (if (normalizedRect.top > 0) spacing / 2 else 0f)
                            val right = normalizedRect.right * width - (if (normalizedRect.right < 1f) spacing / 2 else 0f)
                            val bottom = normalizedRect.bottom * height - (if (normalizedRect.bottom < 1f) spacing / 2 else 0f)
                            val rect = RectF(left, top, right, bottom)
                            
                            if (rect.contains(x, y)) {
                                targetUri = imageUris[i]
                                targetIndex = i
                                break
                            }
                        }
                        
                        if (targetUri != null && targetUri != swapHoverUri) {
                            val newUris = imageUris.toMutableList()
                            val sourceIndex = newUris.indexOf(swapHoverUri)
                            if (sourceIndex != -1 && targetIndex != -1) {
                                newUris[sourceIndex] = targetUri
                                newUris[targetIndex] = swapHoverUri!!
                                imageUris = newUris
                                
                                imageOffsets.remove(swapHoverUri)
                                imageOffsets.remove(targetUri)
                            }
                        }
                    }
                }
                isSwapMode = false
                swapHoverUri = null
                activeUri = null
                invalidate()
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isSwapMode = false
                swapHoverUri = null
                activeUri = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getCenterCropMatrix(bitmap: Bitmap, targetRect: RectF, uri: Uri, isExport: Boolean, exportScale: Float): Matrix {
        val matrix = Matrix()
        val scale: Float
        var baseDx = 0f
        var baseDy = 0f

        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height

        if (bitmapWidth * targetRect.height() > targetRect.width() * bitmapHeight) {
            scale = targetRect.height() / bitmapHeight.toFloat()
            baseDx = (targetRect.width() - bitmapWidth * scale) * 0.5f
        } else {
            scale = targetRect.width() / bitmapWidth.toFloat()
            baseDy = (targetRect.height() - bitmapHeight * scale) * 0.5f
        }
        
        val extraZoom = imageScales[uri] ?: 1f
        val finalScale = scale * extraZoom

        val offset = imageOffsets[uri] ?: android.graphics.PointF(0f, 0f)
        
        val scaledWidth = bitmapWidth * finalScale
        val scaledHeight = bitmapHeight * finalScale
        
        // baseDx logic updated for zoom: shift the base center depending on how much it scaled
        val zoomShiftX = (bitmapWidth * finalScale - bitmapWidth * scale) * 0.5f
        val zoomShiftY = (bitmapHeight * finalScale - bitmapHeight * scale) * 0.5f
        
        val minX = targetRect.width() - scaledWidth
        val maxX = 0f
        
        var finalDx = baseDx - zoomShiftX + (offset.x * exportScale)
        if (finalDx < minX) finalDx = minX
        if (finalDx > maxX) finalDx = maxX
        
        val minY = targetRect.height() - scaledHeight
        val maxY = 0f
        
        var finalDy = baseDy - zoomShiftY + (offset.y * exportScale)
        if (finalDy < minY) finalDy = minY
        if (finalDy > maxY) finalDy = maxY
        
        if (!isExport) {
            offset.x = (finalDx - (baseDx - zoomShiftX)) / exportScale
            offset.y = (finalDy - (baseDy - zoomShiftY)) / exportScale
            imageOffsets[uri] = offset
        }

        matrix.setScale(finalScale, finalScale)
        matrix.postTranslate(
            finalDx + targetRect.left,
            finalDy + targetRect.top
        )
        return matrix
    }

    /**
     * Renders a high-resolution version of the collage and returns the Bitmap.
     * Call this from a coroutine.
     */
    suspend fun generateHighResBitmap(outputSize: Int = 2048, ratio: Float = 1f): Bitmap = withContext(Dispatchers.Default) {
        val outputWidth = if (ratio > 1f) outputSize else (outputSize * ratio).toInt()
        val outputHeight = if (ratio < 1f) outputSize else (outputSize / ratio).toInt()
        
        val outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(collageBgColor)

        val currentTemplate = template ?: return@withContext outputBitmap
        val highResSpacing = spacing * (outputWidth.toFloat() / width.toFloat())
        val outerMargin = highResSpacing / 2
        val drawWidth = outputWidth.toFloat() - highResSpacing
        val drawHeight = outputHeight.toFloat() - highResSpacing

        // We load high-res bitmaps just for saving
        for (i in 0 until minOf(imageUris.size, currentTemplate.bounds.size)) {
            val uri = imageUris[i]
            val normalizedRect = currentTemplate.bounds[i]

            // Use Glide synchronously on background thread
            val bitmap = Glide.with(context)
                .asBitmap()
                .load(uri)
                .override(outputWidth, outputHeight)
                .submit()
                .get()

            val left = outerMargin + normalizedRect.left * drawWidth + (if (normalizedRect.left > 0) highResSpacing / 2 else 0f)
            val top = outerMargin + normalizedRect.top * drawHeight + (if (normalizedRect.top > 0) highResSpacing / 2 else 0f)
            val right = outerMargin + normalizedRect.right * drawWidth - (if (normalizedRect.right < 1f) highResSpacing / 2 else 0f)
            val bottom = outerMargin + normalizedRect.bottom * drawHeight - (if (normalizedRect.bottom < 1f) highResSpacing / 2 else 0f)
            
            val targetRect = RectF(left, top, right, bottom)
            
            canvas.save()
            if (cornerRadius > 0f) {
                val highResCornerRadius = cornerRadius * (outputWidth.toFloat() / width.toFloat())
                val path = android.graphics.Path()
                path.addRoundRect(targetRect, highResCornerRadius, highResCornerRadius, android.graphics.Path.Direction.CW)
                canvas.clipPath(path)
            } else {
                canvas.clipRect(targetRect)
            }
            
            val matrix = getCenterCropMatrix(bitmap, targetRect, uri, true, outputWidth.toFloat() / width.toFloat())
            canvas.drawBitmap(bitmap, matrix, null)
            
            canvas.restore()
        }

        outputBitmap
    }
}
