package com.sk.gallery.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.sk.gallery.data.local.AppPreferences
import com.sk.gallery.model.FileEntry
import com.sk.gallery.model.HierarchyIndex

import com.sk.gallery.util.applySort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MediaRepository(private val context: Context) {

    companion object {
        private const val TAG = "GalleryBySK"

        @Volatile
        private var INSTANCE: MediaRepository? = null

        fun getInstance(context: Context): MediaRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MediaRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val scanner = MediaStoreScanner(context)
    private val preferences = AppPreferences(context)
    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var isScanning = false

    private val _mediaFlow = MutableStateFlow<List<FileEntry>>(emptyList())
    val mediaFlow: StateFlow<List<FileEntry>> = _mediaFlow.asStateFlow()

    private val _manifestFlow = MutableStateFlow<HierarchyIndex?>(null)
    val manifestFlow: StateFlow<HierarchyIndex?> = _manifestFlow.asStateFlow()

    private var contentObserver: ContentObserver? = null

    init {
        loadInitialMedia()
        registerContentObserver()
        
        // Listen for sync completion to refresh cloud badges instantly
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.sk.gallery.SYNC_COMPLETED") {
                    Log.d(TAG, "MediaRepository: Sync completed broadcast received, reloading manifest from disk...")
                    reloadManifestFromDisk()
                }
            }
        }
        val filter = android.content.IntentFilter("com.sk.gallery.SYNC_COMPLETED")
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context).registerReceiver(receiver, filter)
    }

    fun notifyFavoritesChanged() {
        val current = _mediaFlow.value
        if (current.isNotEmpty()) {
            _mediaFlow.value = ArrayList(current)
        }
    }

    fun removeEntriesInstantly(entriesToRemove: List<FileEntry>) {
        val removeIds = entriesToRemove.map { it.hashId }.toSet()
        val updatedList = _mediaFlow.value.filterNot { removeIds.contains(it.hashId) }
        _mediaFlow.value = updatedList

        val currentManifest = _manifestFlow.value
        if (currentManifest != null) {
            val updatedMap = currentManifest.entries.filterKeys { !removeIds.contains(it) }.toMutableMap()
            val newManifest = currentManifest.copy(entries = updatedMap)
            _manifestFlow.value = newManifest
            
            repositoryScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    scanner.updateManifestLocally { _ -> newManifest }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save manifest after instant removal", e)
                }
            }
        }
    }

    fun removeEntryInstantly(entry: FileEntry) {
        removeEntriesInstantly(listOf(entry))
    }

    fun loadInitialMedia() {
        if (isScanning) return
        repositoryScope.launch {
            if (isScanning) return@launch
            isScanning = true
            try {
                // Step 1: Load cached manifest from disk for instant response
                val cached = scanner.loadLocalManifest()
                if (cached != null && cached.entries.isNotEmpty()) {
                    Log.d(TAG, "MediaRepository: Loaded ${cached.entries.size} cached items.")
                    _manifestFlow.value = cached
                    _mediaFlow.value = cached.entries.values.toList()
                }

                // Step 2: Perform fast background MediaStore scan to sync newest items
                val freshManifest = scanner.scanMediaStore()
                _manifestFlow.value = freshManifest
                _mediaFlow.value = freshManifest.entries.values.toList()
                Log.d(TAG, "MediaRepository: MediaStore scan complete with ${freshManifest.entries.size} items.")
            } catch (e: Exception) {
                Log.e(TAG, "MediaRepository: Error loading media", e)
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Lightweight reload: reads the manifest file from disk (which SyncWorker has already
     * updated with cloudFileId values) and pushes the entries into the UI flows.
     * Does NOT re-scan MediaStore — avoids the expense and the isScanning guard.
     */
    private fun reloadManifestFromDisk() {
        repositoryScope.launch {
            try {
                val manifest = scanner.loadLocalManifest()
                if (manifest != null && manifest.entries.isNotEmpty()) {
                    _manifestFlow.value = manifest
                    _mediaFlow.value = manifest.entries.values.toList()
                    Log.d(TAG, "MediaRepository: Reloaded ${manifest.entries.size} entries from disk (cloud badges refreshed).")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaRepository: Failed to reload manifest from disk", e)
            }
        }
    }

    private fun registerContentObserver() {
        if (contentObserver != null) return

        val handler = Handler(Looper.getMainLooper())
        
        val scanRunnable = object : Runnable {
            override fun run() {
                if (isScanning) {
                    // If already scanning, reschedule to ensure we catch the latest changes
                    handler.postDelayed(this, 500)
                    return
                }
                repositoryScope.launch {
                    isScanning = true
                    try {
                        val freshManifest = scanner.scanMediaStore()
                        _manifestFlow.value = freshManifest
                        _mediaFlow.value = freshManifest.entries.values.toList()
                        Log.d(TAG, "MediaRepository: ContentObserver scan complete")
                    } catch (e: Exception) {
                        Log.e(TAG, "MediaRepository: ContentObserver scan failed", e)
                    } finally {
                        isScanning = false
                    }
                }
            }
        }

        contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                Log.d(TAG, "MediaRepository: ContentObserver detected media change! Queuing refresh...")
                handler.removeCallbacks(scanRunnable)
                handler.postDelayed(scanRunnable, 1000) // 1s debounce to allow MediaStore to finish indexing
            }
        }

        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            context.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
            Log.d(TAG, "MediaRepository: ContentObserver successfully registered.")
        } catch (e: Exception) {
            Log.w(TAG, "MediaRepository: Failed to register ContentObserver", e)
        }
    }

    fun getFavorites(): List<FileEntry> {
        val favSet = preferences.getFavorites()
        return _mediaFlow.value.filter { favSet.contains(it.hashId) }.applySort(preferences)
    }

    fun getCameraMedia(): List<FileEntry> {
        return _mediaFlow.value.filter {
            it.relativePath.contains("DCIM/Camera", ignoreCase = true) ||
                    it.relativePath.contains("DCIM/", ignoreCase = true)
        }
    }

    fun getScreenshots(): List<FileEntry> {
        return _mediaFlow.value.filter {
            it.relativePath.contains("Screenshots", ignoreCase = true)
        }
    }

    fun getVideos(): List<FileEntry> {
        return _mediaFlow.value.filter {
            it.mimeType.startsWith("video", ignoreCase = true)
        }
    }

    fun getRecentMedia(): List<FileEntry> {
        return _mediaFlow.value.applySort(preferences)
    }

    fun clearCloudStatus() {
        repositoryScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val newManifest = scanner.updateManifestLocally { currentManifest ->
                    val manifest = currentManifest ?: _manifestFlow.value
                    if (manifest != null) {
                        val newEntries = manifest.entries.mapValues { it.value.copy(cloudFileId = null) }
                        manifest.copy(
                            lastUpdatedTimestamp = System.currentTimeMillis(),
                            entries = newEntries.toMutableMap()
                        )
                    } else null
                }
                
                if (newManifest != null) {
                    _manifestFlow.value = newManifest
                    _mediaFlow.value = newManifest.entries.values.toList()
                    Log.d(TAG, "MediaRepository: Cleared cloud status for all files.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaRepository: Failed to clear cloud status", e)
            }
        }
    }

    fun setCloudStatusForFile(hashId: String, cloudFileId: String) {
        repositoryScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var success = false
            for (i in 0..4) { // Retry for up to 5 seconds
                try {
                    val newManifest = scanner.updateManifestLocally { currentManifest ->
                        val manifest = currentManifest ?: _manifestFlow.value
                        if (manifest != null) {
                            val existing = manifest.entries[hashId]
                            if (existing != null) {
                                val updatedEntries = manifest.entries.toMutableMap()
                                updatedEntries[hashId] = existing.copy(cloudFileId = cloudFileId)
                                manifest.copy(
                                    lastUpdatedTimestamp = System.currentTimeMillis(),
                                    entries = updatedEntries
                                )
                            } else null
                        } else null
                    }
                    
                    if (newManifest != null) {
                        _manifestFlow.value = newManifest
                        _mediaFlow.value = newManifest.entries.values.toList()
                        Log.d(TAG, "MediaRepository: Set cloud status for file $hashId")
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MediaRepository: Failed to set cloud status", e)
                }
                
                if (!success) {
                    kotlinx.coroutines.delay(1000) // Wait 1 second before retrying
                }
            }
            
            if (!success) {
                Log.w(TAG, "MediaRepository: Could not find file $hashId to set cloud status after 5 attempts.")
            }
        }
    }
}
