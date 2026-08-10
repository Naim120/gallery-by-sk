package com.sk.gallery.ui.viewer

import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.sk.gallery.R
import com.sk.gallery.databinding.ActivityVideoEditorBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class VideoEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoEditorBinding
    
    private var videoPath: String? = null
    private var trimStartMs: Long = 0L
    private var trimEndMs: Long = 0L
    private var videoDurationMs: Long = 0L
    
    private var volumePercentage: Int = 100
    private var rotationDegrees: Int = 0
    private var cropAspectRatio: Float? = null

    // Normalized Crop State (coordinates from 0f to 1f relative to the overlay view)
    private var normCropLeft = 0f
    private var normCropRight = 1f
    private var normCropTop = 0f
    private var normCropBottom = 1f
    private var isCropActive = false

    // Pinch/Pan Cropping State (within cropOverlay limits)
    private var zoomScale = 1.0f
    private var panX = 0f
    private var panY = 0f
    
    private lateinit var scaleGestureDetector: android.view.ScaleGestureDetector
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var currentSubCategory: String? = null // "TRIM", "SOUND", "ROTATE", "CROP", "ASPECT_RATIO" or null
    private var isSeeking = false
    
    private val playbackCheckHandler = Handler(Looper.getMainLooper())
    private val playbackCheckRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val currentPos = mp.currentPosition.toLong()
                
                // Keep playback within trim range
                if (currentPos >= trimEndMs) {
                    mp.seekTo(trimStartMs.toInt())
                }
                
                // Update playback seekbar progress (only if user is not seeking)
                if (!isSeeking) {
                    binding.seekbarPlayback.progress = mp.currentPosition
                    binding.tvPlaybackCurrent.text = formatDuration(mp.currentPosition.toLong())
                }
            }
            playbackCheckHandler.postDelayed(this, 100)
        }
    }

    private var progressHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var transformer: Transformer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoPath = intent.getStringExtra("VIDEO_PATH")
        if (videoPath == null || !File(videoPath!!).exists()) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPlayer()
        setupControls()
        updateCropOptionsSelection(R.id.btn_crop_free)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentSubCategory != null) {
                    exitSubCategory()
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupPlayer() {
        val textureView = binding.videoEditorPlayer
        
        // Setup Gesture Detectors for Crop Pinch & Pan
        scaleGestureDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                if (currentSubCategory == "CROP" || currentSubCategory == "ASPECT_RATIO") {
                    zoomScale *= detector.scaleFactor
                    zoomScale = zoomScale.coerceIn(1.0f, 5.0f)
                    updateVideoTransform()
                    return true
                }
                return false
            }
        })

        // Route Touch events depending on pointer count
        textureView.setOnTouchListener { _, event ->
            if (currentSubCategory == "CROP" || currentSubCategory == "ASPECT_RATIO") {
                scaleGestureDetector.onTouchEvent(event)
                
                if (event.pointerCount > 1) {
                    // Two fingers -> process texture scale/pan
                    if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        panX += dx
                        panY += dy
                        updateVideoTransform()
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                    if (event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                } else {
                    // Single finger -> let cropOverlay handle dragging corner handles or moving the crop rectangle
                    binding.cropOverlay.onTouchEvent(event)
                }
            }
            true
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer?.setSurface(Surface(surfaceTexture))
                updateVideoTransform()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                updateVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                mediaPlayer?.setSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }

        initializeMediaPlayer()

        binding.btnVideoPlayPause.setOnClickListener {
            val mp = mediaPlayer ?: return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause()
                binding.btnVideoPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                mp.start()
                binding.btnVideoPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }
    }

    private fun initializeMediaPlayer() {
        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(videoPath)
                setOnPreparedListener { mp ->
                    videoDurationMs = mp.duration.toLong()
                    trimStartMs = 0L
                    trimEndMs = videoDurationMs

                    // Setup trim range slider
                    binding.rangeSliderTrim.valueFrom = 0f
                    binding.rangeSliderTrim.valueTo = videoDurationMs.toFloat()
                    binding.rangeSliderTrim.values = listOf(0f, videoDurationMs.toFloat())

                    binding.tvTrimStart.text = formatDuration(0)
                    binding.tvTrimEnd.text = formatDuration(videoDurationMs)

                    // Setup video playback seekbar
                    binding.seekbarPlayback.max = videoDurationMs.toInt()
                    binding.seekbarPlayback.progress = 0
                    binding.tvPlaybackTotal.text = formatDuration(videoDurationMs)
                    binding.tvPlaybackCurrent.text = formatDuration(0)

                    // Sync media player volume state
                    val volFloat = volumePercentage / 100f
                    mp.setVolume(volFloat, volFloat)

                    // Setup visual layout aspect ratio
                    updatePlayerAspectRatio(cropAspectRatio)

                    // If texture view surface is already available, attach it
                    if (binding.videoEditorPlayer.isAvailable) {
                        mp.setSurface(Surface(binding.videoEditorPlayer.surfaceTexture!!))
                    }

                    seekTo(1)
                    start()
                    binding.btnVideoPlayPause.setImageResource(R.drawable.ic_pause)
                }

                setOnCompletionListener {
                    seekTo(trimStartMs.toInt())
                    start()
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    private fun updateVideoTransform() {
        val textureView = binding.videoEditorPlayer
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        val mp = mediaPlayer ?: return
        val videoWidth = mp.videoWidth.toFloat()
        val videoHeight = mp.videoHeight.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val matrix = android.graphics.Matrix()

        // TextureView internally stretches the raw decoded texture to fill
        // its layout bounds (viewWidth x viewHeight). We must first UNDO
        // that stretch so we work from 1:1 pixel mapping, then apply our
        // own FIT scaling.

        // Step 1: Undo the TextureView's built-in stretch.
        // Move pivot to center, scale raw pixels back to real video size.
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        matrix.postScale(videoWidth / viewWidth, videoHeight / viewHeight, cx, cy)

        // Step 2: Apply rotation around center (operates on real video pixels now).
        matrix.postRotate(rotationDegrees.toFloat(), cx, cy)

        // Step 3: Compute FIT scale for the (possibly rotated) video.
        val rotW = if (rotationDegrees % 180 != 0) videoHeight else videoWidth
        val rotH = if (rotationDegrees % 180 != 0) videoWidth else videoHeight
        val fitScale = Math.min(viewWidth / rotW, viewHeight / rotH)

        // Calculate actual FIT-scaled video bounds centered on-screen (excluding black bars)
        val fittedW = rotW * fitScale
        val fittedH = rotH * fitScale
        val left = (viewWidth - fittedW) / 2f
        val top = (viewHeight - fittedH) / 2f
        val videoBounds = android.graphics.RectF(left, top, left + fittedW, top + fittedH)
        binding.cropOverlay.setVideoBounds(videoBounds)

        // Step 4: Apply FIT scale (and any user zoom).
        val totalScale = fitScale * zoomScale
        matrix.postScale(totalScale, totalScale, cx, cy)

        // Step 5: Apply user pan offset.
        matrix.postTranslate(panX, panY)

        textureView.setTransform(matrix)
    }

    private fun setupControls() {
        // Cancel/Back top button
        binding.btnVideoCancel.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Export
        binding.btnVideoExport.setOnClickListener {
            exportVideo()
        }

        // Playback progress seekbar scrubbing
        binding.seekbarPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var wasPlaying = false
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        mediaPlayer?.seekTo(progress.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
                    } else {
                        mediaPlayer?.seekTo(progress)
                    }
                    binding.tvPlaybackCurrent.text = formatDuration(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                wasPlaying = mediaPlayer?.isPlaying == true
                mediaPlayer?.pause()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Seek to the final thumb position before resuming
                val pos = seekBar?.progress ?: 0
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    mediaPlayer?.seekTo(pos.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
                } else {
                    mediaPlayer?.seekTo(pos)
                }
                isSeeking = false
                if (wasPlaying) {
                    mediaPlayer?.start()
                }
            }
        })

        // Main Navigation Buttons
        binding.btnMainTrim.setOnClickListener {
            showSubCategory("TRIM")
        }
        binding.btnMainSound.setOnClickListener {
            showSubCategory("SOUND")
        }
        binding.btnMainRotate.setOnClickListener {
            showSubCategory("ROTATE")
        }
        binding.btnMainCrop.setOnClickListener {
            showSubCategory("CROP")
        }
        binding.btnMainAspectRatio.setOnClickListener {
            showSubCategory("ASPECT_RATIO")
        }

        // Sub Actions Done (checkmark)
        binding.btnSubDone.setOnClickListener {
            exitSubCategory()
        }

        // --- TRIM CONTROLS ---
        binding.rangeSliderTrim.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                mediaPlayer?.pause()
                binding.btnVideoPlayPause.setImageResource(R.drawable.ic_play)
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                mediaPlayer?.start()
                binding.btnVideoPlayPause.setImageResource(R.drawable.ic_pause)
            }
        })

        binding.rangeSliderTrim.addOnChangeListener { slider, _, _ ->
            val newStart = slider.values[0].toLong()
            val newEnd = slider.values[1].toLong()

            if (newStart != trimStartMs) {
                mediaPlayer?.seekTo(newStart.toInt())
                trimStartMs = newStart
            } else if (newEnd != trimEndMs) {
                mediaPlayer?.seekTo(newEnd.toInt())
                trimEndMs = newEnd
            }

            binding.tvTrimStart.text = formatDuration(trimStartMs)
            binding.tvTrimEnd.text = formatDuration(trimEndMs)
        }

        // --- SOUND CONTROLS ---
        binding.seekbarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                volumePercentage = progress
                binding.tvVolumePercentage.text = "$progress%"
                
                // Update player volume dynamically
                val volFloat = progress / 100f
                mediaPlayer?.setVolume(volFloat, volFloat)

                // Update icon representation
                if (progress == 0) {
                    binding.imgSoundIcon.setImageResource(R.drawable.ic_volume_off)
                } else {
                    binding.imgSoundIcon.setImageResource(R.drawable.ic_volume_up)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- ROTATE CONTROLS ---
        binding.btnActionRotate90.setOnClickListener {
            rotationDegrees = (rotationDegrees + 90) % 360
            updatePlayerAspectRatio(cropAspectRatio)
        }

        // --- CROP PRESET CONTROLS ---
        binding.btnCropPresetFree.setOnClickListener {
            binding.cropOverlay.setAspectRatio(null)
            updateCropPresetSelection(R.id.btn_crop_preset_free)
            binding.cropOverlay.post { syncNormalizedCrop() }
        }
        binding.btnCropPreset11.setOnClickListener {
            binding.cropOverlay.setAspectRatio(1.0f)
            updateCropPresetSelection(R.id.btn_crop_preset_1_1)
            binding.cropOverlay.post { syncNormalizedCrop() }
        }
        binding.btnCropPreset169.setOnClickListener {
            binding.cropOverlay.setAspectRatio(16f / 9f)
            updateCropPresetSelection(R.id.btn_crop_preset_16_9)
            binding.cropOverlay.post { syncNormalizedCrop() }
        }
        binding.btnCropPreset916.setOnClickListener {
            binding.cropOverlay.setAspectRatio(9f / 16f)
            updateCropPresetSelection(R.id.btn_crop_preset_9_16)
            binding.cropOverlay.post { syncNormalizedCrop() }
        }
        binding.btnCropPreset43.setOnClickListener {
            binding.cropOverlay.setAspectRatio(4f / 3f)
            updateCropPresetSelection(R.id.btn_crop_preset_4_3)
            binding.cropOverlay.post { syncNormalizedCrop() }
        }
        binding.btnResetCrop.setOnClickListener {
            binding.cropOverlay.setAspectRatio(null)
            updateCropPresetSelection(R.id.btn_crop_preset_free)
            isCropActive = false
            zoomScale = 1.0f
            panX = 0f
            panY = 0f
            updateVideoTransform()
        }

        // --- ASPECT RATIO CONTROLS ---
        binding.btnCropFree.setOnClickListener {
            cropAspectRatio = null
            updateCropOptionsSelection(R.id.btn_crop_free)
            binding.cropOverlay.setAspectRatio(null)
            isCropActive = false
            updatePlayerAspectRatio(null)
        }
        binding.btnCrop11.setOnClickListener {
            cropAspectRatio = 1.0f
            updateCropOptionsSelection(R.id.btn_crop_1_1)
            binding.cropOverlay.setAspectRatio(1.0f)
            syncNormalizedCrop()
            updatePlayerAspectRatio(1.0f)
        }
        binding.btnCrop169.setOnClickListener {
            cropAspectRatio = 16f / 9f
            updateCropOptionsSelection(R.id.btn_crop_16_9)
            binding.cropOverlay.setAspectRatio(16f / 9f)
            syncNormalizedCrop()
            updatePlayerAspectRatio(16f / 9f)
        }
        binding.btnCrop916.setOnClickListener {
            cropAspectRatio = 9f / 16f
            updateCropOptionsSelection(R.id.btn_crop_9_16)
            binding.cropOverlay.setAspectRatio(9f / 16f)
            syncNormalizedCrop()
            updatePlayerAspectRatio(9f / 16f)
        }
        binding.btnCrop43.setOnClickListener {
            cropAspectRatio = 4f / 3f
            updateCropOptionsSelection(R.id.btn_crop_4_3)
            binding.cropOverlay.setAspectRatio(4f / 3f)
            syncNormalizedCrop()
            updatePlayerAspectRatio(4f / 3f)
        }
    }

    private fun syncNormalizedCrop() {
        val cropRect = binding.cropOverlay.cropRect
        val bounds = binding.cropOverlay.videoBounds
        if (!bounds.isEmpty && !cropRect.isEmpty) {
            normCropLeft = ((cropRect.left - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            normCropRight = ((cropRect.right - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            normCropTop = ((cropRect.top - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            normCropBottom = ((cropRect.bottom - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            
            // Mark crop active if it's smaller than the full video bounds
            val isFullCoverage = normCropLeft < 0.01f && normCropTop < 0.01f && normCropRight > 0.99f && normCropBottom > 0.99f
            isCropActive = !isFullCoverage
        }
    }

    private fun showSubCategory(category: String) {
        currentSubCategory = category
        
        // Hide all sub menus
        binding.layoutVideoTrimOptions.visibility = View.GONE
        binding.layoutVideoSoundOptions.visibility = View.GONE
        binding.layoutVideoRotateOptions.visibility = View.GONE
        binding.layoutVideoCropGestureOptions.visibility = View.GONE
        binding.layoutVideoAspectRatioOptions.visibility = View.GONE

        // Toggle crop overlay visibility
        if (category == "CROP" || category == "ASPECT_RATIO") {
            binding.cropOverlay.visibility = View.VISIBLE
            binding.cropOverlay.post {
                val bounds = binding.cropOverlay.videoBounds
                if (!bounds.isEmpty) {
                    if (isCropActive) {
                        // Restore coordinates relative to the video bounds
                        binding.cropOverlay.cropRect.set(
                            bounds.left + normCropLeft * bounds.width(),
                            bounds.top + normCropTop * bounds.height(),
                            bounds.left + normCropRight * bounds.width(),
                            bounds.top + normCropBottom * bounds.height()
                        )
                        binding.cropOverlay.invalidate()
                    } else {
                        binding.cropOverlay.setAspectRatio(cropAspectRatio)
                    }
                }
            }
        } else {
            binding.cropOverlay.visibility = View.GONE
        }

        // Update player layout (when entering crop mode, we show the full FIT video)
        updatePlayerAspectRatio(cropAspectRatio)

        // Hide main bottom bar, show sub-action bar
        binding.layoutVideoMainOptions.visibility = View.GONE
        binding.layoutVideoSubActions.visibility = View.VISIBLE

        when (category) {
            "TRIM" -> {
                binding.layoutVideoTrimOptions.visibility = View.VISIBLE
                binding.tvSubTitle.text = "Trim Video"
            }
            "SOUND" -> {
                binding.layoutVideoSoundOptions.visibility = View.VISIBLE
                binding.tvSubTitle.text = "Volume"
            }
            "ROTATE" -> {
                binding.layoutVideoRotateOptions.visibility = View.VISIBLE
                binding.tvSubTitle.text = "Rotate"
            }
            "CROP" -> {
                binding.layoutVideoCropGestureOptions.visibility = View.VISIBLE
                binding.tvSubTitle.text = "Crop"
            }
            "ASPECT_RATIO" -> {
                binding.layoutVideoAspectRatioOptions.visibility = View.VISIBLE
                binding.tvSubTitle.text = "Aspect Ratio"
            }
        }
    }

    private fun exitSubCategory() {
        if (currentSubCategory == "CROP" || currentSubCategory == "ASPECT_RATIO") {
            syncNormalizedCrop()
        }
        
        currentSubCategory = null
        binding.cropOverlay.visibility = View.GONE

        // Hide all sub menus
        binding.layoutVideoTrimOptions.visibility = View.GONE
        binding.layoutVideoSoundOptions.visibility = View.GONE
        binding.layoutVideoRotateOptions.visibility = View.GONE
        binding.layoutVideoCropGestureOptions.visibility = View.GONE
        binding.layoutVideoAspectRatioOptions.visibility = View.GONE

        // Update player aspect ratio and transform matrix
        updatePlayerAspectRatio(cropAspectRatio)

        // Show main bottom bar, hide sub-action bar
        binding.layoutVideoSubActions.visibility = View.GONE
        binding.layoutVideoMainOptions.visibility = View.VISIBLE
    }

    private fun updateCropOptionsSelection(selectedId: Int) {
        val ids = listOf(
            R.id.btn_crop_free,
            R.id.btn_crop_1_1,
            R.id.btn_crop_16_9,
            R.id.btn_crop_9_16,
            R.id.btn_crop_4_3
        )
        for (id in ids) {
            val tv = findViewById<android.widget.TextView>(id)
            if (id == selectedId) {
                tv.setTextColor(0xFF00FF00.toInt())
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun updateCropPresetSelection(selectedId: Int) {
        val ids = listOf(
            R.id.btn_crop_preset_free,
            R.id.btn_crop_preset_1_1,
            R.id.btn_crop_preset_16_9,
            R.id.btn_crop_preset_9_16,
            R.id.btn_crop_preset_4_3
        )
        for (id in ids) {
            val tv = findViewById<android.widget.TextView>(id)
            if (id == selectedId) {
                tv.setTextColor(0xFF00FF00.toInt())
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun updatePlayerAspectRatio(ratio: Float?) {
        val player = binding.videoEditorPlayer
        val container = binding.videoPreviewContainer
        container.post {
            val containerWidth = container.width
            val containerHeight = container.height
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            // Always use match_parent so the TextureView fills the container;
            // the matrix transform handles FIT scaling to avoid zoom-in.
            val lp = player.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = android.view.Gravity.CENTER
            player.layoutParams = lp

            // Sync crop overlay to also fill the container
            val lpOverlay = binding.cropOverlay.layoutParams as FrameLayout.LayoutParams
            lpOverlay.width = FrameLayout.LayoutParams.MATCH_PARENT
            lpOverlay.height = FrameLayout.LayoutParams.MATCH_PARENT
            lpOverlay.gravity = android.view.Gravity.CENTER
            binding.cropOverlay.layoutParams = lpOverlay

            player.post {
                updateVideoTransform()
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun exportVideo() {
        val originalFile = File(videoPath!!)
        val dir = originalFile.parentFile
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputName = "video_edited_$timeStamp.mp4"
        val outputFile = File(dir, outputName)

        // Setup loading
        binding.layoutVideoLoading.visibility = View.VISIBLE
        binding.tvLoadingText.text = "Exporting video... 0%"
        binding.progressBarExport.progress = 0

        // Pause preview
        mediaPlayer?.pause()
        binding.btnVideoPlayPause.setImageResource(R.drawable.ic_play)

        // Build MediaItem with trim clipping
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(originalFile))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
            .build()

        // Build Effects
        val videoEffects = ArrayList<Effect>()

        // 1. Rotation Effect (if selected)
        if (rotationDegrees != 0) {
            videoEffects.add(ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(rotationDegrees.toFloat())
                .build())
        }


        if (isCropActive) {
            // Map [0..1] to Media3 Crop's [-1..1] coordinate system (Y is inverted)
            val left = (normCropLeft * 2f - 1f).coerceIn(-1f, 1f)
            val right = (normCropRight * 2f - 1f).coerceIn(-1f, 1f)
            val bottom = ((1f - normCropBottom) * 2f - 1f).coerceIn(-1f, 1f)
            val top = ((1f - normCropTop) * 2f - 1f).coerceIn(-1f, 1f)

            android.util.Log.d("VideoEditor", "CROP EXPORT: isCropActive=$isCropActive norm=[$normCropLeft,$normCropTop,$normCropRight,$normCropBottom] media3=[$left,$right,$bottom,$top]")

            videoEffects.add(androidx.media3.effect.Crop(left, right, bottom, top))
        }

        // Build Audio processors for custom volume level
        val audioProcessors = ArrayList<androidx.media3.common.audio.AudioProcessor>()
        if (volumePercentage > 0 && volumePercentage < 100) {
            val channelMixingAudioProcessor = ChannelMixingAudioProcessor()
            val volFloat = volumePercentage / 100f
            val matrixMono = ChannelMixingMatrix(1, 1, floatArrayOf(volFloat))
            val matrixStereo = ChannelMixingMatrix(2, 2, floatArrayOf(volFloat, 0f, 0f, volFloat))
            channelMixingAudioProcessor.putChannelMixingMatrix(matrixMono)
            channelMixingAudioProcessor.putChannelMixingMatrix(matrixStereo)
            audioProcessors.add(channelMixingAudioProcessor)
        }

        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(volumePercentage == 0)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()

        // Setup Media3 Transformer
        try {
            transformer = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        runOnUiThread {
                            onExportSuccess(outputFile)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        runOnUiThread {
                            onExportError(exportException)
                        }
                    }
                })
                .build()

            transformer?.start(editedMediaItem, outputFile.absolutePath)
            startProgressPolling()
        } catch (e: Exception) {
            onExportError(e)
        }
    }

    private fun startProgressPolling() {
        val progressHolder = ProgressHolder()
        progressRunnable = object : Runnable {
            override fun run() {
                transformer?.let { tr ->
                    val state = tr.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        binding.progressBarExport.progress = progressHolder.progress
                        binding.tvLoadingText.text = "Exporting video... ${progressHolder.progress}%"
                    }
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        progressHandler.postDelayed(this, 300)
                    }
                }
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let {
            progressHandler.removeCallbacks(it)
        }
        progressRunnable = null
    }

    private fun onExportSuccess(outputFile: File) {
        stopProgressPolling()
        binding.layoutVideoLoading.visibility = View.GONE
        Toast.makeText(this, "Video saved successfully!", Toast.LENGTH_LONG).show()

        // Scan the output file into media library
        android.media.MediaScannerConnection.scanFile(
            this,
            arrayOf(outputFile.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        // Return result
        val data = Intent().apply {
            putExtra("NEW_VIDEO_PATH", outputFile.absolutePath)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun onExportError(e: Throwable) {
        stopProgressPolling()
        binding.layoutVideoLoading.visibility = View.GONE
        Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        mediaPlayer?.start()
        binding.btnVideoPlayPause.setImageResource(R.drawable.ic_pause)
    }

    override fun onResume() {
        super.onResume()
        playbackCheckHandler.post(playbackCheckRunnable)
        val mp = mediaPlayer
        if (mp != null && !mp.isPlaying && binding.btnVideoPlayPause.tag == "playing") {
            mp.start()
        }
    }

    override fun onPause() {
        super.onPause()
        playbackCheckHandler.removeCallbacks(playbackCheckRunnable)
        val mp = mediaPlayer
        if (mp != null && mp.isPlaying) {
            mp.pause()
            binding.btnVideoPlayPause.tag = "playing"
        } else {
            binding.btnVideoPlayPause.tag = "paused"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressPolling()
        transformer?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
