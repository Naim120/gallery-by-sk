package com.sk.gallery.ui.viewer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = Color.parseColor("#B3000000") // 70% semi-transparent dark overlay
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
    }

    val cropRect = RectF()
    private var aspect: Float? = null
    val videoBounds = RectF()

    // Touch handling state
    private var activePointerId = -1
    private var touchMode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val handleRadius = 60f // Touch threshold radius in pixels for corner handles

    enum class Mode {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE
    }

    fun setAspectRatio(ratio: Float?) {
        aspect = ratio
        resetCropRect()
    }

    fun setVideoBounds(bounds: RectF) {
        videoBounds.set(bounds)
        resetCropRect()
    }

    private fun getActiveBounds(): RectF {
        return if (videoBounds.isEmpty) {
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        } else {
            videoBounds
        }
    }

    fun resetCropRect() {
        if (width > 0 && height > 0) {
            val bounds = getActiveBounds()
            val w = bounds.width()
            val h = bounds.height()
            
            val ratio = aspect
            if (ratio == null) {
                // Free crop: start with 90% of the active bounds
                cropRect.set(
                    bounds.left + w * 0.05f,
                    bounds.top + h * 0.05f,
                    bounds.left + w * 0.95f,
                    bounds.top + h * 0.95f
                )
            } else {
                // Constrained crop aspect ratio within active bounds
                val boundsRatio = w / h
                if (ratio > boundsRatio) {
                    val cropW = w * 0.9f
                    val cropH = cropW / ratio
                    val top = bounds.top + (h - cropH) / 2f
                    cropRect.set(
                        bounds.left + w * 0.05f,
                        top,
                        bounds.left + w * 0.95f,
                        top + cropH
                    )
                } else {
                    val cropH = h * 0.9f
                    val cropW = cropH * ratio
                    val left = bounds.left + (w - cropW) / 2f
                    cropRect.set(
                        left,
                        bounds.top + h * 0.05f,
                        left + cropW,
                        bounds.top + h * 0.95f
                    )
                }
            }
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetCropRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.isEmpty) return

        // 1. Draw semi-transparent background outside cropRect
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, overlayPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, overlayPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, overlayPaint)

        // 2. Draw border rectangle
        canvas.drawRect(cropRect, borderPaint)

        // 3. Draw corner handles (L-shaped)
        val len = 48f
        // Top Left
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + len, handlePaint)
        // Top Right
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right - len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + len, handlePaint)
        // Bottom Left
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + len, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - len, handlePaint)
        // Bottom Right
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right - len, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right, cropRect.bottom - len, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = x
                lastY = y
                touchMode = getTouchMode(x, y)
                return touchMode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == Mode.NONE) return false

                val dx = x - lastX
                val dy = y - lastY

                val minSize = 120f
                val bounds = getActiveBounds()

                when (touchMode) {
                    Mode.MOVE -> {
                        cropRect.offset(dx, dy)
                        // Keep completely within active bounds
                        if (cropRect.left < bounds.left) cropRect.offset(bounds.left - cropRect.left, 0f)
                        if (cropRect.top < bounds.top) cropRect.offset(0f, bounds.top - cropRect.top)
                        if (cropRect.right > bounds.right) cropRect.offset(bounds.right - cropRect.right, 0f)
                        if (cropRect.bottom > bounds.bottom) cropRect.offset(0f, bounds.bottom - cropRect.bottom)
                    }
                    Mode.TOP_LEFT -> {
                        val newLeft = (cropRect.left + dx).coerceIn(bounds.left, cropRect.right - minSize)
                        val newTop = (cropRect.top + dy).coerceIn(bounds.top, cropRect.bottom - minSize)
                        if (aspect != null) {
                            adjustAspectRatioTopLeft(newLeft, newTop, bounds)
                        } else {
                            cropRect.left = newLeft
                            cropRect.top = newTop
                        }
                    }
                    Mode.TOP_RIGHT -> {
                        val newRight = (cropRect.right + dx).coerceIn(cropRect.left + minSize, bounds.right)
                        val newTop = (cropRect.top + dy).coerceIn(bounds.top, cropRect.bottom - minSize)
                        if (aspect != null) {
                            adjustAspectRatioTopRight(newRight, newTop, bounds)
                        } else {
                            cropRect.right = newRight
                            cropRect.top = newTop
                        }
                    }
                    Mode.BOTTOM_LEFT -> {
                        val newLeft = (cropRect.left + dx).coerceIn(bounds.left, cropRect.right - minSize)
                        val newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, bounds.bottom)
                        if (aspect != null) {
                            adjustAspectRatioBottomLeft(newLeft, newBottom, bounds)
                        } else {
                            cropRect.left = newLeft
                            cropRect.bottom = newBottom
                        }
                    }
                    Mode.BOTTOM_RIGHT -> {
                        val newRight = (cropRect.right + dx).coerceIn(cropRect.left + minSize, bounds.right)
                        val newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, bounds.bottom)
                        if (aspect != null) {
                            adjustAspectRatioBottomRight(newRight, newBottom, bounds)
                        } else {
                            cropRect.right = newRight
                            cropRect.bottom = newBottom
                        }
                    }
                    else -> {}
                }

                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = Mode.NONE
                activePointerId = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchMode(x: Float, y: Float): Mode {
        if (dist(x, y, cropRect.left, cropRect.top) < handleRadius) return Mode.TOP_LEFT
        if (dist(x, y, cropRect.right, cropRect.top) < handleRadius) return Mode.TOP_RIGHT
        if (dist(x, y, cropRect.left, cropRect.bottom) < handleRadius) return Mode.BOTTOM_LEFT
        if (dist(x, y, cropRect.right, cropRect.bottom) < handleRadius) return Mode.BOTTOM_RIGHT
        if (cropRect.contains(x, y)) return Mode.MOVE
        return Mode.NONE
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun adjustAspectRatioTopLeft(newLeft: Float, newTop: Float, bounds: RectF) {
        val ratio = aspect ?: return
        val w = cropRect.right - newLeft
        val h = w / ratio
        val calculatedTop = cropRect.bottom - h
        if (calculatedTop >= bounds.top) {
            cropRect.left = newLeft
            cropRect.top = calculatedTop
        } else {
            val maxH = cropRect.bottom - bounds.top
            val maxW = maxH * ratio
            cropRect.left = cropRect.right - maxW
            cropRect.top = bounds.top
        }
    }

    private fun adjustAspectRatioTopRight(newRight: Float, newTop: Float, bounds: RectF) {
        val ratio = aspect ?: return
        val w = newRight - cropRect.left
        val h = w / ratio
        val calculatedTop = cropRect.bottom - h
        if (calculatedTop >= bounds.top) {
            cropRect.right = newRight
            cropRect.top = calculatedTop
        } else {
            val maxH = cropRect.bottom - bounds.top
            val maxW = maxH * ratio
            cropRect.right = cropRect.left + maxW
            cropRect.top = bounds.top
        }
    }

    private fun adjustAspectRatioBottomLeft(newLeft: Float, newBottom: Float, bounds: RectF) {
        val ratio = aspect ?: return
        val w = cropRect.right - newLeft
        val h = w / ratio
        val calculatedBottom = cropRect.top + h
        if (calculatedBottom <= bounds.bottom) {
            cropRect.left = newLeft
            cropRect.bottom = calculatedBottom
        } else {
            val maxH = bounds.bottom - cropRect.top
            val maxW = maxH * ratio
            cropRect.left = cropRect.right - maxW
            cropRect.bottom = bounds.bottom
        }
    }

    private fun adjustAspectRatioBottomRight(newRight: Float, newBottom: Float, bounds: RectF) {
        val ratio = aspect ?: return
        val w = newRight - cropRect.left
        val h = w / ratio
        val calculatedBottom = cropRect.top + h
        if (calculatedBottom <= bounds.bottom) {
            cropRect.right = newRight
            cropRect.bottom = calculatedBottom
        } else {
            val maxH = bounds.bottom - cropRect.top
            val maxW = maxH * ratio
            cropRect.right = cropRect.left + maxW
            cropRect.bottom = bounds.bottom
        }
    }
}
