package com.sk.gallery.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class FastScrollHelper(
    private val recyclerView: RecyclerView,
    private val thumbView: View,
    private val dateBubble: TextView?,
    private val getDateAtPosition: (Int) -> Long?
) {

    private var isDragging = false
    private var recyclerViewHeight = 0
    private var thumbHeight = 0
    private var lastDatePosition = -1
    private var isGlidePaused = false

    private val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val reusableDate = Date() // Zero allocation during drag

    // 48dp edge width for touch interception
    private val edgeWidthPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        48f,
        recyclerView.resources.displayMetrics
    )

    private val resumeGlideRunnable = Runnable {
        if (isGlidePaused) {
            try {
                com.bumptech.glide.Glide.with(recyclerView.context).resumeRequests()
                isGlidePaused = false
            } catch (_: Exception) {
                // Intentionally ignored, Glide context might be destroyed
            }
        }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!isDragging) {
                updateThumbPosition()
            } else {
                updateDateBubble()
            }
            
            // Prevent Glide from saturating the thread during ultra-fast scrolling
            if (abs(dy) > 150) {
                if (!isGlidePaused) {
                    try {
                        com.bumptech.glide.Glide.with(recyclerView.context).pauseRequests()
                        isGlidePaused = true
                    } catch (_: Exception) {}
                }
                recyclerView.removeCallbacks(resumeGlideRunnable)
                recyclerView.postDelayed(resumeGlideRunnable, 150)
            }
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            if (!isDragging) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    hideThumbDelayed()
                } else {
                    showThumb()
                }
            }
            
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                recyclerView.removeCallbacks(resumeGlideRunnable)
                resumeGlideRunnable.run()
            }
        }
    }

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateMeasurements()
        if (!isDragging) {
            updateThumbPosition()
        }
    }

    private val touchListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            val action = e.actionMasked
            val x = e.x
            val y = e.y

            if (action == MotionEvent.ACTION_DOWN) {
                // Start dragging only if touching directly on the thumb (with some vertical padding)
                // This prevents accidental track-taps from intercepting horizontal ViewPager swipes
                if (thumbView.visibility == View.VISIBLE && x >= rv.width - edgeWidthPx) {
                    val thumbTop = thumbView.translationY
                    val thumbBottom = thumbTop + thumbHeight
                    
                    val isTouchingThumb = y >= thumbTop - edgeWidthPx && y <= thumbBottom + edgeWidthPx
                    
                    if (isTouchingThumb) {
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                        setSwipeRefreshEnabled(false)
                        isDragging = true
                        lastDatePosition = -1 // Reset to ensure bubble updates
                        
                        updateMeasurements()
                        showThumb()
                        
                        // We do NOT blindly show the bubble here anymore. 
                        // It will be shown in updateDateBubble() if it actually has text.
                        thumbView.isPressed = true
                        
                        handleDrag(y)
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            if (!isDragging) return

            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    handleDrag(e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    setSwipeRefreshEnabled(true)
                    thumbView.isPressed = false
                    hideThumbDelayed()
                    
                    dateBubble?.animate()?.cancel()
                    if (dateBubble?.visibility == View.VISIBLE) {
                        dateBubble.animate().alpha(0f).setDuration(200).setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (!isDragging) dateBubble.visibility = View.INVISIBLE
                            }
                        }).start()
                    }
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    private val adapterDataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            if (!isDragging) updateThumbPosition()
        }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            if (!isDragging) updateThumbPosition()
        }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            if (!isDragging) updateThumbPosition()
        }
    }

    init {
        thumbView.visibility = View.INVISIBLE
        dateBubble?.visibility = View.INVISIBLE

        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutChangeListener)
        recyclerView.addOnItemTouchListener(touchListener)
        
        recyclerView.adapter?.registerAdapterDataObserver(adapterDataObserver)

        recyclerView.post {
            updateMeasurements()
        }
    }

    fun release() {
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnLayoutChangeListener(layoutChangeListener)
        recyclerView.removeOnItemTouchListener(touchListener)
        try {
            recyclerView.adapter?.unregisterAdapterDataObserver(adapterDataObserver)
        } catch (_: Exception) {}
        recyclerView.removeCallbacks(resumeGlideRunnable)
        thumbView.removeCallbacks(hideRunnable)
        thumbView.animate().cancel()
        dateBubble?.animate()?.cancel()
    }

    private fun updateMeasurements() {
        recyclerViewHeight = recyclerView.height
        thumbHeight = thumbView.height
    }

    private fun handleDrag(y: Float) {
        val adapter = recyclerView.adapter ?: return
        val itemCount = adapter.itemCount
        if (itemCount == 0) return

        val maxThumbY = recyclerViewHeight - thumbHeight
        if (maxThumbY <= 0) return

        // Constrain Y between 0 and max height
        val constrainedY = y.coerceIn(0f, recyclerViewHeight.toFloat())
        val thumbY = (constrainedY - thumbHeight / 2f).coerceIn(0f, maxThumbY.toFloat())
        
        thumbView.translationY = thumbY
        if (dateBubble != null) {
            val maxBubbleY = recyclerViewHeight - dateBubble.height
            val bubbleY = (thumbY + thumbHeight / 2f - dateBubble.height / 2f).coerceIn(0f, maxBubbleY.toFloat())
            dateBubble.translationY = bubbleY
        }

        val percentage = thumbY / maxThumbY
        val currentOffset = recyclerView.computeVerticalScrollOffset()
        val range = recyclerView.computeVerticalScrollRange()
        val extent = recyclerView.computeVerticalScrollExtent()
        val maxScroll = range - extent

        if (maxScroll > 0) {
            val targetOffset = (percentage * maxScroll).toInt()
            val delta = targetOffset - currentOffset
            
            if (delta != 0) {
                // If jumping more than ~2 screen heights instantly, use layout manager to prevent UI freeze
                if (kotlin.math.abs(delta) > recyclerViewHeight * 2) {
                    val targetPos = (percentage * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
                    val layoutManager = recyclerView.layoutManager
                    if (layoutManager is LinearLayoutManager) {
                        layoutManager.scrollToPositionWithOffset(targetPos, 0)
                    } else {
                        recyclerView.scrollToPosition(targetPos)
                    }
                } else {
                    // For normal fast dragging, use smooth pixel-perfect scrolling
                    recyclerView.scrollBy(0, delta)
                }
            }
        }
        
        // Date bubble updates dynamically via onScrolled -> updateDateBubble()
    }

    private fun updateDateBubble() {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        
        if (position != RecyclerView.NO_POSITION && position != lastDatePosition) {
            lastDatePosition = position
            val dateMillis = getDateAtPosition(position)
            if (dateMillis != null && dateMillis > 0L && dateBubble != null) {
                reusableDate.time = dateMillis
                dateBubble.text = dateFormat.format(reusableDate)
                
                // Fade in bubble if it was hidden
                if (dateBubble.visibility != View.VISIBLE) {
                    dateBubble.alpha = 0f
                    dateBubble.visibility = View.VISIBLE
                    dateBubble.animate().cancel()
                    dateBubble.animate().alpha(1f).setDuration(200).setListener(null).start()
                }
            } else if (dateBubble != null) {
                dateBubble.text = ""
                // Hide if no valid date
                if (dateBubble.visibility == View.VISIBLE) {
                    dateBubble.animate().cancel()
                    dateBubble.animate().alpha(0f).setDuration(200).setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            dateBubble.visibility = View.INVISIBLE
                        }
                    }).start()
                }
            }
        }
    }

    private fun updateThumbPosition() {
        val adapter = recyclerView.adapter ?: return
        if (adapter.itemCount == 0) {
            thumbView.visibility = View.INVISIBLE
            return
        }

        val offset = recyclerView.computeVerticalScrollOffset()
        val range = recyclerView.computeVerticalScrollRange()
        val extent = recyclerView.computeVerticalScrollExtent()

        val maxScroll = range - extent
        if (maxScroll <= 0) {
            thumbView.visibility = View.INVISIBLE
            return
        }

        val percentage = offset.toFloat() / maxScroll

        updateMeasurements()
        val maxThumbY = recyclerViewHeight - thumbHeight
        val thumbY = percentage * maxThumbY

        thumbView.translationY = thumbY.coerceIn(0f, maxThumbY.toFloat())
    }

    private val hideRunnable = Runnable {
        if (!isDragging) {
            thumbView.animate().cancel()
            thumbView.animate().alpha(0f).setDuration(300).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isDragging) thumbView.visibility = View.INVISIBLE
                }
            }).start()
        }
    }

    private fun showThumb() {
        thumbView.removeCallbacks(hideRunnable)
        thumbView.animate().cancel()
        thumbView.visibility = View.VISIBLE
        thumbView.alpha = 1f
    }

    private fun hideThumbDelayed() {
        thumbView.removeCallbacks(hideRunnable)
        thumbView.postDelayed(hideRunnable, 1500)
    }

    private fun setSwipeRefreshEnabled(enabled: Boolean) {
        var parent = recyclerView.parent
        while (parent != null) {
            if (parent is androidx.swiperefreshlayout.widget.SwipeRefreshLayout) {
                parent.isEnabled = enabled
                break
            }
            parent = parent.parent
        }
    }
}
