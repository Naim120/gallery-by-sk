package com.sk.gallery.ui.vault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.sk.gallery.R
import com.sk.gallery.databinding.ActivityVaultSettingsBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class VaultSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVaultSettingsBinding

    private var pendingAction = "" // "export" or "import"
    private var isLaunchingExternal = false

    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    if (pendingAction == "export") {
                        startExportFlow()
                    } else if (pendingAction == "import") {
                        startImportFlow()
                    }
                }
            } catch (e: ApiException) {
                Toast.makeText(this, "Sign-in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        setTheme(R.style.Theme_GalleryBySK_PrivateSafe)
        binding = ActivityVaultSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnChangePin.setOnClickListener {
            isLaunchingExternal = true
            val intent = Intent(this, VaultSecurityActivity::class.java).apply {
                putExtra(VaultSecurityActivity.EXTRA_CHANGE_PIN, true)
            }
            startActivity(intent)
        }

        prefs = com.sk.gallery.data.local.AppPreferences(this)

        binding.tabExport.setOnClickListener {
            setTabSelected("export")
        }

        binding.tabImport.setOnClickListener {
            setTabSelected("import")
        }

        binding.btnPrimaryAction.setOnClickListener {
            requestSignIn()
        }

        // Set default tab
        setTabSelected("export")
        
        // Update user email
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && account.email != null) {
            binding.tvUserEmail.text = account.email
        } else {
            binding.tvUserEmail.text = "Not signed in"
        }

        setupWorkObservers()
        setupSyncActionButtons()
        checkPausedState()
        updateLastInfoLogs()
    }

    private fun setTabSelected(action: String) {
        pendingAction = action
        if (action == "export") {
            binding.tabExport.setBackgroundResource(R.drawable.bg_pill_white)
            binding.tabExport.setTextColor(android.graphics.Color.BLACK)
            binding.tabImport.setBackgroundResource(R.drawable.bg_pill_transparent)
            binding.tabImport.setTextColor(android.graphics.Color.WHITE)
            binding.btnPrimaryAction.text = "Export Private Safe"
            binding.layoutDeviceDropdown.visibility = android.view.View.GONE
        } else {
            binding.tabImport.setBackgroundResource(R.drawable.bg_pill_white)
            binding.tabImport.setTextColor(android.graphics.Color.BLACK)
            binding.tabExport.setBackgroundResource(R.drawable.bg_pill_transparent)
            binding.tabExport.setTextColor(android.graphics.Color.WHITE)
            binding.btnPrimaryAction.text = "Import Private Safe"
            // We might show the device dropdown if they have backups available.
        }
        
        // Refresh UI state for the new tab
        updateLastInfoLogs()
        checkPausedState()
        
        // Also if something is running, we might need to manually trigger updateSyncUI 
        // by observing again, but LiveData will just re-trigger if we just re-evaluate visibility
        val wm = androidx.work.WorkManager.getInstance(this)
        val exportRunning = wm.getWorkInfosForUniqueWork(EXPORT_WORK_NAME).get()?.firstOrNull()?.state?.isFinished == false
        val importRunning = wm.getWorkInfosForUniqueWork(IMPORT_WORK_NAME).get()?.firstOrNull()?.state?.isFinished == false
        
        if (action == "export") {
            if (exportRunning || prefs.isPrivateSafeExportPaused()) {
                binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutSyncProgress.visibility = android.view.View.GONE
            }
        } else {
            if (importRunning || prefs.isPrivateSafeImportPaused()) {
                binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutSyncProgress.visibility = android.view.View.GONE
            }
        }
    }

    private fun requestSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))) {
            binding.tvUserEmail.text = account.email ?: "Signed in"
            if (pendingAction == "export") startExportFlow()
            else startImportFlow()
        } else {
            val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
                .build()
            val client = GoogleSignIn.getClient(this, signInOptions)
            isLaunchingExternal = true
            signInLauncher.launch(client.signInIntent)
        }
    }

    private fun startExportFlow() {
        val prefs = com.sk.gallery.data.local.AppPreferences(this)
        
        val vaultEntries = com.sk.gallery.data.PrivateVaultManager.getVaultEntries(this)
        if (vaultEntries.isEmpty()) {
            Toast.makeText(this, "Your Private Safe is empty! Nothing to back up.", Toast.LENGTH_SHORT).show()
            return
        }

        var passphrase = prefs.getCloudPassphrase()
        
        if (passphrase == null) {
            passphrase = com.sk.gallery.data.crypto.CryptoManager.generatePassphrase()
            prefs.setCloudPassphrase(passphrase)
            
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Recovery Passphrase")
                .setMessage("Please save these 12 words securely. You will need them to recover your Private Safe on another device:\n\n$passphrase")
                .setPositiveButton("I have saved it") { _, _ ->
                    enqueueExportWorker()
                }
                .setCancelable(false)
                .show()
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Export Backup")
                .setMessage("Your Private Safe files will be securely encrypted and synced to Google Drive. Continue?")
                .setPositiveButton("Start Sync") { _, _ ->
                    enqueueExportWorker()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private lateinit var prefs: com.sk.gallery.data.local.AppPreferences
    private var isPausingExport = false
    private var isPausingImport = false

    private fun setupWorkObservers() {
        val wm = androidx.work.WorkManager.getInstance(this)
        
        wm.getWorkInfosForUniqueWorkLiveData(EXPORT_WORK_NAME).observe(this) { workInfos ->
            val workInfo = workInfos?.firstOrNull()
            if (workInfo != null) {
                updateSyncUI(workInfo, isExport = true)
            } else {
                checkPausedState()
            }
        }

        wm.getWorkInfosForUniqueWorkLiveData(IMPORT_WORK_NAME).observe(this) { workInfos ->
            val workInfo = workInfos?.firstOrNull()
            if (workInfo != null) {
                updateSyncUI(workInfo, isExport = false)
            } else {
                checkPausedState()
            }
        }
    }

    private fun updateSyncUI(workInfo: androidx.work.WorkInfo, isExport: Boolean) {
        val state = workInfo.state
        if (state == androidx.work.WorkInfo.State.RUNNING || state == androidx.work.WorkInfo.State.ENQUEUED) {
            if ((isExport && pendingAction == "export") || (!isExport && pendingAction == "import")) {
                binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutSyncProgress.visibility = android.view.View.GONE
            }
            
            val progress = workInfo.progress.getInt("progress", 0)
            val status = if (isExport) {
                if (isPausingExport) "Pausing export..." else (workInfo.progress.getString("status") ?: "Syncing...")
            } else {
                if (isPausingImport) "Pausing import..." else (workInfo.progress.getString("status") ?: "Restoring...")
            }
            
            binding.tvSyncStatus.text = status
            binding.tvSyncPercentage.text = "$progress%"
            binding.progressBarSync.progress = progress
            
            if (status.contains("All files already uploaded", ignoreCase = true)) {
                binding.btnPauseSync.visibility = android.view.View.GONE
                binding.btnCancelSync.visibility = android.view.View.VISIBLE
                binding.btnCancelSync.text = "Done"
            } else {
                if (isExport && isPausingExport) {
                    binding.btnPauseSync.isEnabled = false
                    binding.btnPauseSync.text = "Pausing"
                    binding.btnPauseSync.visibility = android.view.View.VISIBLE
                } else if (!isExport && isPausingImport) {
                    binding.btnPauseSync.isEnabled = false
                    binding.btnPauseSync.text = "Pausing"
                    binding.btnPauseSync.visibility = android.view.View.VISIBLE
                } else {
                    binding.btnPauseSync.isEnabled = true
                    binding.btnPauseSync.text = "Pause"
                    binding.btnPauseSync.visibility = if (state == androidx.work.WorkInfo.State.RUNNING) android.view.View.VISIBLE else android.view.View.GONE
                }
                binding.btnCancelSync.visibility = android.view.View.VISIBLE
                binding.btnCancelSync.text = "Cancel"
            }
            binding.btnResumeSync.visibility = android.view.View.GONE
        } else if (state == androidx.work.WorkInfo.State.SUCCEEDED) {
            if (binding.layoutSyncProgress.visibility == android.view.View.VISIBLE) {
                binding.tvSyncStatus.text = "Completed"
                binding.tvSyncPercentage.text = "100%"
                binding.progressBarSync.progress = 100
                binding.btnPauseSync.visibility = android.view.View.GONE
                binding.btnResumeSync.visibility = android.view.View.GONE
                binding.btnCancelSync.visibility = android.view.View.VISIBLE
                binding.btnCancelSync.text = "Done"
                updateLastInfoLogs()
            } else {
                checkPausedState()
            }
        } else {
            if (binding.layoutSyncProgress.visibility == android.view.View.VISIBLE) {
                val wm = androidx.work.WorkManager.getInstance(this)
                val exportRunning = wm.getWorkInfosForUniqueWork(EXPORT_WORK_NAME).get()?.firstOrNull()?.state?.isFinished == false
                val importRunning = wm.getWorkInfosForUniqueWork(IMPORT_WORK_NAME).get()?.firstOrNull()?.state?.isFinished == false
                if (!exportRunning && !importRunning && !prefs.isPrivateSafeExportPaused() && !prefs.isPrivateSafeImportPaused()) {
                    binding.layoutSyncProgress.visibility = android.view.View.GONE
                }
            }
            checkPausedState()
            updateLastInfoLogs()
        }
    }

    private fun checkPausedState() {
        if (prefs.isPrivateSafeExportPaused()) {
            isPausingExport = false
            if (pendingAction == "export") {
                binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutSyncProgress.visibility = android.view.View.GONE
            }
            binding.tvSyncStatus.text = prefs.getPrivateSafeExportStatus()
            val progress = prefs.getPrivateSafeExportProgress()
            binding.tvSyncPercentage.text = "$progress%"
            binding.progressBarSync.progress = progress
            
            binding.btnPauseSync.isEnabled = true
            binding.btnPauseSync.text = "Pause"
            binding.btnPauseSync.visibility = android.view.View.GONE
            binding.btnResumeSync.visibility = android.view.View.VISIBLE
            binding.btnCancelSync.visibility = android.view.View.VISIBLE
        } else if (prefs.isPrivateSafeImportPaused()) {
            isPausingImport = false
            if (pendingAction == "import") {
                binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutSyncProgress.visibility = android.view.View.GONE
            }
            binding.tvSyncStatus.text = prefs.getPrivateSafeImportStatus()
            val progress = prefs.getPrivateSafeImportProgress()
            binding.tvSyncPercentage.text = "$progress%"
            binding.progressBarSync.progress = progress
            
            binding.btnPauseSync.isEnabled = true
            binding.btnPauseSync.text = "Pause"
            binding.btnPauseSync.visibility = android.view.View.GONE
            binding.btnResumeSync.visibility = android.view.View.VISIBLE
            binding.btnCancelSync.visibility = android.view.View.VISIBLE
        } else {
            val wm = androidx.work.WorkManager.getInstance(this)
            val exportRunning = wm.getWorkInfosForUniqueWork(EXPORT_WORK_NAME).get()?.firstOrNull()?.state?.isFinished == false
            val importRunning = wm.getWorkInfosForUniqueWork(IMPORT_WORK_NAME).get()?.firstOrNull()?.state?.isFinished == false
            
            if (pendingAction == "export") {
                if (!exportRunning && !prefs.isPrivateSafeExportPaused()) {
                    binding.layoutSyncProgress.visibility = android.view.View.GONE
                } else if (exportRunning || prefs.isPrivateSafeExportPaused()) {
                    binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
                }
            } else {
                if (!importRunning && !prefs.isPrivateSafeImportPaused()) {
                    binding.layoutSyncProgress.visibility = android.view.View.GONE
                } else if (importRunning || prefs.isPrivateSafeImportPaused()) {
                    binding.layoutSyncProgress.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

    private fun updateLastInfoLogs() {
        val lastExport = prefs.getPrivateSafeLastExport()
        if (lastExport != null && pendingAction == "export") {
            binding.tvLastExportInfo.visibility = android.view.View.VISIBLE
            binding.tvLastExportInfo.text = lastExport
        } else {
            binding.tvLastExportInfo.visibility = android.view.View.GONE
        }

        val lastImport = prefs.getPrivateSafeLastImport()
        if (lastImport != null && pendingAction == "import") {
            binding.tvLastImportInfo.visibility = android.view.View.VISIBLE
            binding.tvLastImportInfo.text = lastImport
        } else {
            binding.tvLastImportInfo.visibility = android.view.View.GONE
        }
    }

    private fun setupSyncActionButtons() {
        binding.btnPauseSync.setOnClickListener {
            val wm = androidx.work.WorkManager.getInstance(this)
            val exportState = wm.getWorkInfosForUniqueWork(EXPORT_WORK_NAME).get()?.firstOrNull()?.state
            val importState = wm.getWorkInfosForUniqueWork(IMPORT_WORK_NAME).get()?.firstOrNull()?.state
            
            if (exportState == androidx.work.WorkInfo.State.RUNNING) {
                isPausingExport = true
                binding.btnPauseSync.isEnabled = false
                binding.btnPauseSync.text = "Pausing"
                binding.tvSyncStatus.text = "Pausing export..."
                prefs.setPrivateSafeExportPaused(true)
                
                val currentWorkInfo = wm.getWorkInfosForUniqueWork(EXPORT_WORK_NAME).get()?.firstOrNull()
                val currentProgress = currentWorkInfo?.progress?.getInt("progress", 0) ?: 0
                val currentStatus = currentWorkInfo?.progress?.getString("status") ?: ""
                prefs.setPrivateSafeExportProgress(currentProgress)
                prefs.setPrivateSafeExportStatus(if (currentStatus.isNotBlank()) "Paused ($currentStatus)" else "Paused")
                
                wm.cancelUniqueWork(EXPORT_WORK_NAME)
                Toast.makeText(this, "Pausing export...", Toast.LENGTH_SHORT).show()
            } else if (importState == androidx.work.WorkInfo.State.RUNNING) {
                isPausingImport = true
                binding.btnPauseSync.isEnabled = false
                binding.btnPauseSync.text = "Pausing"
                binding.tvSyncStatus.text = "Pausing import..."
                prefs.setPrivateSafeImportPaused(true)
                
                val currentWorkInfo = wm.getWorkInfosForUniqueWork(IMPORT_WORK_NAME).get()?.firstOrNull()
                val currentProgress = currentWorkInfo?.progress?.getInt("progress", 0) ?: 0
                val currentStatus = currentWorkInfo?.progress?.getString("status") ?: ""
                prefs.setPrivateSafeImportProgress(currentProgress)
                prefs.setPrivateSafeImportStatus(if (currentStatus.isNotBlank()) "Paused ($currentStatus)" else "Paused")
                
                wm.cancelUniqueWork(IMPORT_WORK_NAME)
                Toast.makeText(this, "Pausing import...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResumeSync.setOnClickListener {
            if (prefs.isPrivateSafeExportPaused()) {
                prefs.setPrivateSafeExportPaused(false)
                enqueueExportWorker()
            } else if (prefs.isPrivateSafeImportPaused()) {
                prefs.setPrivateSafeImportPaused(false)
                enqueueImportWorker(prefs.getPrivateSafeImportFolder())
            }
        }

        binding.btnCancelSync.setOnClickListener {
            val isDone = binding.btnCancelSync.text.toString().equals("Done", ignoreCase = true)
            if (isDone) {
                val wm = androidx.work.WorkManager.getInstance(this)
                wm.cancelUniqueWork(EXPORT_WORK_NAME)
                wm.cancelUniqueWork(IMPORT_WORK_NAME)
                prefs.setPrivateSafeExportPaused(false)
                prefs.setPrivateSafeImportPaused(false)
                isPausingExport = false
                isPausingImport = false
                binding.layoutSyncProgress.visibility = android.view.View.GONE
                return@setOnClickListener
            }

            if (pendingAction == "export") {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Cancel Upload")
                    .setMessage("Cancel will delete the uploaded data that were uploaded from this device to save space in Google Drive. You can always pause and continue later. Are you sure you want to cancel the upload and delete the uploaded data?")
                    .setPositiveButton("Pause") { _, _ ->
                        binding.btnPauseSync.performClick()
                    }
                    .setNegativeButton("Cancel & Delete") { _, _ ->
                        val wm = androidx.work.WorkManager.getInstance(this)
                        wm.cancelUniqueWork(EXPORT_WORK_NAME)
                        prefs.setPrivateSafeExportPaused(false)
                        prefs.setPrivateSafeExportStatus("")
                        prefs.setPrivateSafeExportProgress(0)
                        isPausingExport = false
                        binding.layoutSyncProgress.visibility = android.view.View.GONE
                        
                        // Clear cloud status immediately for instant UI feedback
                        com.sk.gallery.data.PrivateVaultManager.setCloudStatus(this@VaultSettingsActivity, emptySet())
                        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this@VaultSettingsActivity)
                            .sendBroadcast(android.content.Intent("com.sk.gallery.VAULT_SYNC_COMPLETED"))

                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val deviceId = android.provider.Settings.Secure.getString(
                                    contentResolver,
                                    android.provider.Settings.Secure.ANDROID_ID
                                ) ?: "unknown_device"
                                com.sk.gallery.cloud.DriveBackupManager.deleteDeviceFolder(this@VaultSettingsActivity, "${deviceId}-private_safe")
                            } catch (e: Exception) {
                                android.util.Log.e("VaultSettingsActivity", "Failed to delete private safe backup on cancel", e)
                            }
                        }
                        Toast.makeText(this, "Upload Cancelled & Deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Dismiss", null)
                    .show()
            } else {
                val wm = androidx.work.WorkManager.getInstance(this)
                wm.cancelUniqueWork(IMPORT_WORK_NAME)
                prefs.setPrivateSafeImportPaused(false)
                prefs.setPrivateSafeImportStatus("")
                prefs.setPrivateSafeImportProgress(0)
                isPausingImport = false
                binding.layoutSyncProgress.visibility = android.view.View.GONE
                Toast.makeText(this, "Import Cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enqueueExportWorker() {
        Toast.makeText(this, "Export Backup started in background", Toast.LENGTH_SHORT).show()
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.sk.gallery.data.sync.DriveExportWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            EXPORT_WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun startImportFlow() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Scanning Google Drive for Private Safe backups...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val backups = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val drive = com.sk.gallery.cloud.DriveBackupManager.getDriveService(this@VaultSettingsActivity)
                    val list = mutableListOf<Pair<String, String>>()
                    
                    // Fetch all device subdirectories
                    val query = "mimeType = 'application/vnd.google-apps.folder' and 'appDataFolder' in parents and trashed = false"
                    val result = drive.files().list()
                        .setSpaces("appDataFolder")
                        .setQ(query)
                        .setFields("files(id, name)")
                        .execute()
                    
                    val folders = result.files ?: emptyList()
                    for (folder in folders) {
                        if (!folder.name.endsWith("-private_safe")) continue

                        var hasVaultMap = false
                        val mapQuery = "name = 'vault_map.json.enc' and '${folder.id}' in parents and trashed = false"
                        val mapResult = drive.files().list()
                            .setSpaces("appDataFolder")
                            .setQ(mapQuery)
                            .setFields("files(id)")
                            .execute()
                        
                        if (!mapResult.files.isNullOrEmpty()) {
                            hasVaultMap = true
                        }
                        
                        if (hasVaultMap) {
                            val deviceId = folder.name.removeSuffix("-private_safe")
                            // Try to read device info from device_info.json (unencrypted) first
                            var deviceLabel = "Device: $deviceId"
                            var infoLoaded = false
                            
                            val infoQuery = "name = 'device_info.json' and '${folder.id}' in parents and trashed = false"
                            val infoResult = drive.files().list()
                                .setSpaces("appDataFolder")
                                .setQ(infoQuery)
                                .setFields("files(id)")
                                .execute()
                            val infoFile = infoResult.files?.firstOrNull()
                            if (infoFile != null) {
                                val tempInfoFile = java.io.File(cacheDir, "temp_info_${folder.name}.json")
                                try {
                                    com.sk.gallery.cloud.DriveBackupManager.downloadFile(this@VaultSettingsActivity, infoFile.id, tempInfoFile)
                                    val infoJson = tempInfoFile.readText()
                                    val infoMap = com.google.gson.Gson().fromJson(infoJson, Map::class.java)
                                    val name = infoMap["deviceName"] as? String ?: "Unknown"
                                    val model = infoMap["deviceModel"] as? String ?: "Device"
                                    deviceLabel = if (name.equals(model, ignoreCase = true)) name else "$name $model"
                                    infoLoaded = true
                                } catch (e: Exception) {
                                    android.util.Log.e("GalleryBySK", "Failed to read device_info.json", e)
                                } finally {
                                    if (tempInfoFile.exists()) tempInfoFile.delete()
                                }
                            }

                            if (!infoLoaded) {
                                // Try to read device model if hierarchy_index.json.enc exists in the device folder
                                val manifestQuery = "name = 'hierarchy_index.json.enc' and '${folder.id}' in parents and trashed = false"
                                val manifestResult = drive.files().list()
                                    .setSpaces("appDataFolder")
                                    .setQ(manifestQuery)
                                    .setFields("files(id)")
                                    .execute()
                                
                                val manifestFile = manifestResult.files?.firstOrNull()
                                if (manifestFile != null) {
                                    val tempEncFile = java.io.File(cacheDir, "temp_scan_ps_${folder.name}.json.enc")
                                    try {
                                        com.sk.gallery.cloud.DriveBackupManager.downloadFile(this@VaultSettingsActivity, manifestFile.id, tempEncFile)
                                        val tempPlainFile = java.io.File(cacheDir, "temp_scan_ps_${folder.name}.json")
                                        java.io.FileInputStream(tempEncFile).use { input ->
                                            java.io.FileOutputStream(tempPlainFile).use { output ->
                                                com.sk.gallery.util.CryptoManager.decryptStream(input, output)
                                            }
                                        }
                                        val manifestJson = tempPlainFile.readText()
                                        val manifestMap = com.google.gson.Gson().fromJson(manifestJson, Map::class.java)
                                        val name = manifestMap["deviceName"] as? String ?: "Unknown Device"
                                        val model = manifestMap["deviceModel"] as? String ?: ""
                                        deviceLabel = if (name.equals(model, ignoreCase = true)) name else "$name $model"
                                        tempEncFile.delete()
                                        tempPlainFile.delete()
                                    } catch (e: Exception) {
                                        if (tempEncFile.exists()) tempEncFile.delete()
                                    }
                                }
                            }
                            list.add(Pair(folder.id, deviceLabel))
                        }
                    }

                    // Check if root appDataFolder has vault_map.json.enc (legacy fallback)
                    val rootMapQuery = "name = 'vault_map.json.enc' and 'appDataFolder' in parents and trashed = false"
                    val rootMapResult = drive.files().list()
                        .setSpaces("appDataFolder")
                        .setQ(rootMapQuery)
                        .setFields("files(id)")
                        .execute()
                    if (!rootMapResult.files.isNullOrEmpty()) {
                        list.add(Pair("appDataFolder", "Root Private Safe Backup"))
                    }

                    list
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "Error scanning Private Safe backups", e)
                    emptyList<Pair<String, String>>()
                }
            }
            
            progressDialog.dismiss()
            
            if (backups.isEmpty()) {
                Toast.makeText(this@VaultSettingsActivity, "No Private Safe backups found on Google Drive.", Toast.LENGTH_LONG).show()
            } else {
                setupDeviceDropdown(backups)
            }
        }
    }

    private fun setupDeviceDropdown(backups: List<Pair<String, String>>) {
        binding.layoutDeviceDropdown.visibility = android.view.View.VISIBLE
        val deviceLabels = backups.map { it.second }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, deviceLabels)
        binding.spinnerDevices.setAdapter(adapter)
        
        binding.spinnerDevices.setOnItemClickListener { _, _, position, _ ->
            val chosen = backups[position]
            val chosenFolderId = chosen.first
            promptForPassphrase(chosenFolderId, chosen.second)
        }
    }

    private fun promptForPassphrase(deviceFolderId: String, deviceLabel: String) {
        val input = android.widget.EditText(this).apply {
            hint = "12-word passphrase"
            setPadding(24, 24, 24, 24)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enter Passphrase")
            .setMessage("Please enter the 12-word recovery passphrase for $deviceLabel:")
            .setView(input)
            .setPositiveButton("Verify & Import") { _, _ ->
                val phrase = input.text.toString().trim()
                if (phrase.split(" ").size == 12) {
                    verifyPassphraseAndImport(deviceFolderId, phrase)
                } else {
                    Toast.makeText(this, "Invalid passphrase format. Must be 12 words.", Toast.LENGTH_SHORT).show()
                    binding.spinnerDevices.setText("", false)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.spinnerDevices.setText("", false)
            }
            .setCancelable(false)
            .show()
    }

    private fun verifyPassphraseAndImport(deviceFolderId: String, phrase: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Verifying passphrase with backup...")
            setCancelable(false)
            show()
        }
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            var errorReason = "Wrong passphrase."
            val isCorrect = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this@VaultSettingsActivity)
                    if (account == null) {
                        errorReason = "Not signed in to Google Drive."
                        return@withContext false
                    }
                    val driveManager = com.sk.gallery.data.sync.GoogleDriveManager(this@VaultSettingsActivity, account)
                    val drive = com.sk.gallery.cloud.DriveBackupManager.getDriveService(this@VaultSettingsActivity)
                    
                    var targetFolderId = deviceFolderId
                    
                    // Download vault_map.json.enc
                    val mapQuery = "name = 'vault_map.json.enc' and '$targetFolderId' in parents and trashed = false"
                    val mapResult = drive.files().list()
                        .setSpaces("appDataFolder")
                        .setQ(mapQuery)
                        .setFields("files(id)")
                        .execute()
                    
                    val mapFile = mapResult.files?.firstOrNull()
                    if (mapFile == null) {
                        errorReason = "Backup index file not found. Backup may be corrupted."
                        return@withContext false
                    }
                    
                    val tempEncMap = java.io.File(cacheDir, "temp_verify_map.json.enc")
                    val tempPlainMap = java.io.File(cacheDir, "temp_verify_map.json")
                    try {
                        if (driveManager.downloadFile(mapFile.id, tempEncMap)) {
                            val decrypted = com.sk.gallery.data.crypto.CryptoManager.decryptFromCloud(tempEncMap, tempPlainMap, phrase)
                            if (!decrypted) {
                                errorReason = "Wrong passphrase."
                            }
                            decrypted
                        } else {
                            errorReason = "Failed to download backup index file."
                            false
                        }
                    } finally {
                        if (tempEncMap.exists()) tempEncMap.delete()
                        if (tempPlainMap.exists()) tempPlainMap.delete()
                    }
                } catch (e: java.io.IOException) {
                    android.util.Log.e("GalleryBySK", "Network error during verification", e)
                    errorReason = "Network connection failed. Please check your internet."
                    false
                } catch (e: Exception) {
                    android.util.Log.e("GalleryBySK", "Passphrase verification failed", e)
                    errorReason = "An unexpected error occurred during verification."
                    false
                }
            }
            
            progressDialog.dismiss()
            
            if (isCorrect) {
                val checkDialog = android.app.ProgressDialog(this@VaultSettingsActivity).apply {
                    setMessage("Checking backup files...")
                    setCancelable(false)
                    show()
                }
                
                val allFilesExist = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val account = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(this@VaultSettingsActivity) ?: return@withContext false
                        val driveManager = com.sk.gallery.data.sync.GoogleDriveManager(this@VaultSettingsActivity, account)
                        val drive = com.sk.gallery.cloud.DriveBackupManager.getDriveService(this@VaultSettingsActivity)
                        
                        var targetFolderId = deviceFolderId
                        
                        val remoteFiles = driveManager.listEncryptedFiles(targetFolderId)
                        val mediaFiles = remoteFiles.filter { it.name.endsWith(".enc") && it.name != "vault_map.json.enc" }
                        
                        if (mediaFiles.isEmpty()) {
                            false
                        } else {
                            val vaultDir = getDir("PrivateVault", android.content.Context.MODE_PRIVATE)
                            mediaFiles.all { cloudFile ->
                                val originalHashId = cloudFile.name.removeSuffix(".enc")
                                java.io.File(vaultDir, originalHashId).exists()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GalleryBySK", "Error checking if backup files exist locally", e)
                        false
                    }
                }
                
                checkDialog.dismiss()
                
                if (allFilesExist) {
                    Toast.makeText(this@VaultSettingsActivity, "All files from this backup are already imported", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.setCloudPassphrase(phrase)
                    prefs.setPrivateSafeImportFolder(deviceFolderId)
                    enqueueImportWorker(deviceFolderId)
                }
                binding.layoutDeviceDropdown.visibility = android.view.View.GONE
                binding.spinnerDevices.setText("", false)
            } else {
                Toast.makeText(this@VaultSettingsActivity, errorReason, Toast.LENGTH_SHORT).show()
                binding.spinnerDevices.setText("", false)
            }
        }
    }

    private fun enqueueImportWorker(deviceFolderId: String) {
        Toast.makeText(this, "Import Backup started in background", Toast.LENGTH_SHORT).show()
        val inputData = androidx.work.workDataOf("device_folder_id" to deviceFolderId)
        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.sk.gallery.data.sync.DriveImportWorker>()
            .setInputData(inputData)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
            IMPORT_WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        const val EXTRA_CHANGE_PIN = "extra_change_pin"
        const val EXPORT_WORK_NAME = "private_safe_export"
        const val IMPORT_WORK_NAME = "private_safe_import"
    }
    
    override fun onResume() {
        super.onResume()
        isLaunchingExternal = false
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations && !isLaunchingExternal && !isFinishing) {
            finishAffinity()
        }
    }
}
