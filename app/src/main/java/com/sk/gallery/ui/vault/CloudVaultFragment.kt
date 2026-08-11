package com.sk.gallery.ui.vault

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.sk.gallery.R
import com.sk.gallery.auth.AuthState
import com.sk.gallery.auth.AuthViewModel
import com.sk.gallery.auth.GoogleSignInManager
import com.sk.gallery.data.MediaRepository
import com.sk.gallery.data.db.UploadDatabase
import com.sk.gallery.data.db.UploadEntity
import com.sk.gallery.databinding.FragmentCloudVaultBinding
import com.sk.gallery.worker.ExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CloudVaultFragment : Fragment() {

    companion object {
        private const val TAG = "GalleryBySK"
        private const val EXPORT_WORK_NAME = "EXPORT_WORK"
        private const val IMPORT_WORK_NAME = "IMPORT_WORK"
    }

    private var _binding: FragmentCloudVaultBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by viewModels()
    private lateinit var database: UploadDatabase

    private var isDriveExpanded = false
    private var isS3Expanded = false
    private var isPausingExport = false
    private var isPausingImport = false

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.data != null) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    Log.d(TAG, "CloudVaultFragment: Google Sign-In successful for ${account.email}")
                    authViewModel.handleSignInResult(result.data, requireContext())
                } else {
                    Toast.makeText(requireContext(), "Sign in cancelled", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                Log.e(TAG, "CloudVaultFragment: Google Sign-In error (code ${e.statusCode})", e)
                handleSignInError(e)
            }
        }
    }

    private fun handleSignInError(e: ApiException) {
        val message = when (e.statusCode) {
            10 -> "DEVELOPER_ERROR (Status 10): Ensure SHA-1 is registered in Google Cloud Console."
            12500 -> "SIGN_IN_FAILED (Status 12500): Could not complete Google sign-in."
            else -> "Google Sign-In Error (Code ${e.statusCode}): ${e.localizedMessage}"
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Google OAuth Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudVaultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        database = UploadDatabase.getDatabase(requireContext())

        setupExpandableCards()
        setupTabs()
        setupAuthObserver()
        setupActionButtons()
        observeDatabaseProgress()
        observeImportDatabaseProgress()
        observeWorkManager()
    }

    override fun onResume() {
        super.onResume()
        authViewModel.checkExistingAccount(requireContext())
    }

    private fun setupExpandableCards() {
        binding.headerGoogleDrive.setOnClickListener {
            isDriveExpanded = !isDriveExpanded
            binding.contentGoogleDrive.visibility = if (isDriveExpanded) View.VISIBLE else View.GONE
            binding.ivExpandDrive.rotation = if (isDriveExpanded) 180f else 0f
        }

        binding.headerS3Bucket.setOnClickListener {
            isS3Expanded = !isS3Expanded
            binding.contentS3Bucket.visibility = if (isS3Expanded) View.VISIBLE else View.GONE
            binding.ivExpandS3.rotation = if (isS3Expanded) 180f else 0f
        }
    }

    private fun setupTabs() {
        binding.tabExport.setOnClickListener {
            selectTab(0)
        }

        binding.tabImport.setOnClickListener {
            selectTab(1)
        }

        binding.tabDelete.setOnClickListener {
            selectTab(2)
        }
        
        // Initialize default tab
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        val activeColor = requireContext().getColor(R.color.bg_primary)
        val inactiveColor = requireContext().getColor(R.color.text_muted)

        // Reset all tabs
        binding.tabExport.background = null
        binding.tabExport.setTextColor(inactiveColor)
        binding.tabExport.setTypeface(null, android.graphics.Typeface.NORMAL)

        binding.tabImport.background = null
        binding.tabImport.setTextColor(inactiveColor)
        binding.tabImport.setTypeface(null, android.graphics.Typeface.NORMAL)

        binding.tabDelete.background = null
        binding.tabDelete.setTextColor(inactiveColor)
        binding.tabDelete.setTypeface(null, android.graphics.Typeface.NORMAL)

        // Set active
        when (index) {
            0 -> {
                binding.tabExport.setBackgroundResource(R.drawable.bg_tab_active)
                binding.tabExport.setTextColor(activeColor)
                binding.tabExport.setTypeface(null, android.graphics.Typeface.BOLD)

                binding.sectionExport.visibility = View.VISIBLE
                binding.sectionImport.visibility = View.GONE
                binding.sectionDelete.visibility = View.GONE
            }
            1 -> {
                binding.tabImport.setBackgroundResource(R.drawable.bg_tab_active)
                binding.tabImport.setTextColor(activeColor)
                binding.tabImport.setTypeface(null, android.graphics.Typeface.BOLD)

                binding.sectionExport.visibility = View.GONE
                binding.sectionImport.visibility = View.VISIBLE
                binding.sectionDelete.visibility = View.GONE
            }
            2 -> {
                binding.tabDelete.setBackgroundResource(R.drawable.bg_tab_active)
                binding.tabDelete.setTextColor(activeColor)
                binding.tabDelete.setTypeface(null, android.graphics.Typeface.BOLD)

                binding.sectionExport.visibility = View.GONE
                binding.sectionImport.visibility = View.GONE
                binding.sectionDelete.visibility = View.VISIBLE
            }
        }
    }

    private fun setupAuthObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            authViewModel.authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated -> renderAuthenticated(state.account)
                    is AuthState.Unauthenticated -> renderUnauthenticated()
                    is AuthState.Authenticating -> {
                        binding.tvDriveStatusBadge.text = "Authenticating..."
                        binding.btnConnectDrive.isEnabled = false
                    }
                    is AuthState.Error -> {
                        renderUnauthenticated()
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderAuthenticated(account: GoogleSignInAccount) {
        binding.tvDriveStatusBadge.text = "Connected"
        binding.tvDriveStatusBadge.setTextColor(requireContext().getColor(android.R.color.holo_green_light))
        
        binding.layoutDriveUnconnected.visibility = View.GONE
        binding.layoutDriveConnected.visibility = View.VISIBLE
        
        binding.tvAccountName.text = account.displayName ?: "Google Account"
        binding.tvAccountEmail.text = account.email ?: "appDataFolder Vault Active"
        
        account.photoUrl?.let { uri ->
            binding.ivAvatar.imageTintList = null
            com.bumptech.glide.Glide.with(requireContext())
                .load(uri)
                .circleCrop()
                .into(binding.ivAvatar)
        } ?: run {
            binding.ivAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
            binding.ivAvatar.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.accent_primary))
        }
        
        binding.btnDisconnectDrive.setOnClickListener {
            authViewModel.signOut(requireContext())
        }
    }

    private fun renderUnauthenticated() {
        binding.tvDriveStatusBadge.text = "Not Connected"
        binding.tvDriveStatusBadge.setTextColor(requireContext().getColor(android.R.color.darker_gray))
        
        binding.layoutDriveUnconnected.visibility = View.VISIBLE
        binding.layoutDriveConnected.visibility = View.GONE
        
        binding.btnConnectDrive.isEnabled = true
        binding.btnConnectDrive.setOnClickListener {
            val client = GoogleSignInManager.getGoogleSignInClient(requireContext())
            signInLauncher.launch(client.signInIntent)
        }
    }

    private fun setupActionButtons() {
        binding.btnExportNow.setOnClickListener {
            binding.btnExportNow.isEnabled = false
            if (binding.btnExportNow.text.toString() == "Done") {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    database.uploadDao().clearAll()
                    withContext(Dispatchers.Main) {
                        binding.btnExportNow.text = "Backup Now"
                        binding.btnExportNow.isEnabled = true
                    }
                }
            } else {
                startExportProcess()
            }
        }

        binding.btnPauseBackup.setOnClickListener {
            isPausingExport = true
            binding.btnPauseBackup.isEnabled = false
            binding.btnPauseBackup.text = "Pausing..."
            binding.tvUploadStatus.text = "Pausing backup..."
            WorkManager.getInstance(requireContext()).cancelUniqueWork(EXPORT_WORK_NAME)
            Toast.makeText(requireContext(), "Pausing backup...", Toast.LENGTH_SHORT).show()
        }

        binding.btnResumeBackup.setOnClickListener {
            startExportProcess()
        }

        binding.btnCancelBackup.setOnClickListener {
            val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cancel & Discard Backup?")
                .setMessage("Are you sure you want to completely cancel this backup? This will delete any files that have already been uploaded for this device to free up Google Drive space.")
                .setPositiveButton("Yes, Cancel & Delete") { _, _ ->
                    binding.btnPauseBackup.isEnabled = false
                    binding.btnResumeBackup.isEnabled = false
                    binding.btnCancelBackup.isEnabled = false
                    binding.btnCancelBackup.text = "Cancelling..."

                    WorkManager.getInstance(requireContext()).cancelUniqueWork(EXPORT_WORK_NAME)
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        database.uploadDao().clearAll()
                        // Clear cloud status immediately for instant UI feedback
                        com.sk.gallery.data.MediaRepository.getInstance(requireContext()).clearCloudStatus()
                        try {
                            val deviceId = android.provider.Settings.Secure.getString(
                                requireContext().contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID
                            ) ?: "unknown_device"
                            com.sk.gallery.cloud.DriveBackupManager.deleteDeviceFolder(requireContext(), deviceId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete backup files on cancel", e)
                        }
                        withContext(Dispatchers.Main) {
                            binding.layoutExportProgress.visibility = View.GONE
                            binding.btnExportNow.visibility = View.VISIBLE
                            binding.btnExportNow.isEnabled = true
                            binding.btnPauseBackup.visibility = View.GONE
                            binding.btnResumeBackup.visibility = View.GONE
                            binding.btnCancelBackup.visibility = View.GONE

                            // Reset button states
                            binding.btnPauseBackup.isEnabled = true
                            binding.btnResumeBackup.isEnabled = true
                            binding.btnCancelBackup.isEnabled = true
                            binding.btnCancelBackup.text = "Cancel"

                            updateDisconnectButtonState()
                            Toast.makeText(requireContext(), "Backup cancelled and Google Drive data cleared.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Dismiss", null)
            
            if (binding.btnPauseBackup.visibility == View.VISIBLE && binding.btnPauseBackup.isEnabled) {
                builder.setNeutralButton("Just Pause") { _, _ ->
                    binding.btnPauseBackup.performClick()
                }
            }
            
            builder.show()
        }

        binding.btnScanCloud.setOnClickListener {
            binding.btnScanCloud.isEnabled = false
            binding.btnScanCloud.text = "Scanning..."
            startScanCloudProcess()
        }

        binding.btnImportNow.setOnClickListener {
            binding.btnImportNow.isEnabled = false
            if (binding.btnImportNow.text.toString() == "Done") {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    database.importDao().clearAll()
                    withContext(Dispatchers.Main) {
                        binding.btnImportNow.text = "Restore Now"
                        binding.btnImportNow.isEnabled = true
                        binding.layoutImportList.removeAllViews()
                        binding.layoutImportList.visibility = View.GONE
                    }
                }
            } else {
                val selectedBackups = mutableListOf<com.sk.gallery.cloud.BackupInfo>()
                for (i in 0 until binding.layoutImportList.childCount) {
                    val child = binding.layoutImportList.getChildAt(i)
                    if (child is android.widget.CheckBox && child.isChecked) {
                        (child.tag as? com.sk.gallery.cloud.BackupInfo)?.let { selectedBackups.add(it) }
                    }
                }
                if (selectedBackups.isNotEmpty()) {
                    processSelectedBackups(selectedBackups)
                } else {
                    startImportProcess()
                }
            }
        }

        binding.btnPauseImport.setOnClickListener {
            isPausingImport = true
            binding.btnPauseImport.isEnabled = false
            binding.btnPauseImport.text = "Pausing..."
            binding.tvImportStatus.text = "Pausing restore..."
            WorkManager.getInstance(requireContext()).cancelUniqueWork(IMPORT_WORK_NAME)
            Toast.makeText(requireContext(), "Pausing restore...", Toast.LENGTH_SHORT).show()
        }

        binding.btnResumeImport.setOnClickListener {
            startImportProcess()
        }

        binding.btnCancelImport.setOnClickListener {
            binding.btnPauseImport.isEnabled = false
            binding.btnResumeImport.isEnabled = false
            binding.btnCancelImport.isEnabled = false
            binding.btnCancelImport.text = "Cancelling..."
            
            WorkManager.getInstance(requireContext()).cancelUniqueWork(IMPORT_WORK_NAME)
            isPausingImport = false
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                database.importDao().clearAll()
                withContext(Dispatchers.Main) {
                    binding.layoutImportProgress.visibility = View.GONE
                    binding.layoutImportActions.visibility = View.GONE
                    binding.btnScanCloud.visibility = View.VISIBLE
                    binding.layoutImportList.removeAllViews()
                    binding.layoutImportList.visibility = View.GONE
                    binding.btnScanCloud.isEnabled = true
                    binding.btnScanCloud.text = "Scan Google Drive for Backup"
                    
                    binding.btnPauseImport.isEnabled = true
                    binding.btnResumeImport.isEnabled = true
                    binding.btnCancelImport.isEnabled = true
                    binding.btnCancelImport.text = "Cancel"
                    
                    binding.btnPauseImport.visibility = View.GONE
                    binding.btnResumeImport.visibility = View.GONE
                    binding.btnCancelImport.visibility = View.GONE
                    
                    updateDisconnectButtonState()
                    Toast.makeText(requireContext(), "Restore cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnScanDelete.setOnClickListener {
            startScanDeleteProcess()
        }

        binding.btnDeleteConfirm.setOnClickListener {
            val enteredPin = binding.etDeletePin.text.toString()
            val prefs = com.sk.gallery.data.local.AppPreferences(requireContext())
            val correctPin = prefs.getVaultPin()
            
            if (correctPin == null) {
                Toast.makeText(requireContext(), "Please set up a Private Safe PIN first by entering the Private Safe from the Photos/Albums tab.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            
            if (enteredPin != correctPin) {
                Toast.makeText(requireContext(), "Wrong PIN code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedBackups = mutableListOf<com.sk.gallery.cloud.BackupInfo>()
            for (i in 0 until binding.layoutDeleteList.childCount) {
                val child = binding.layoutDeleteList.getChildAt(i)
                if (child is android.widget.CheckBox && child.isChecked) {
                    (child.tag as? com.sk.gallery.cloud.BackupInfo)?.let { selectedBackups.add(it) }
                }
            }

            if (selectedBackups.isEmpty()) return@setOnClickListener

            binding.btnDeleteConfirm.isEnabled = false
            binding.btnDeleteConfirm.text = "Deleting..."

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    for (backup in selectedBackups) {
                        val localDeviceId = android.provider.Settings.Secure.getString(requireContext().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                        if (backup.deviceId == localDeviceId) {
                            // Clear cloud status immediately before blocking network calls
                            com.sk.gallery.data.MediaRepository.getInstance(requireContext()).clearCloudStatus()
                            com.sk.gallery.data.PrivateVaultManager.setCloudStatus(requireContext(), emptySet())
                        }
                        
                        try {
                            com.sk.gallery.cloud.DriveBackupManager.deleteDeviceFolder(requireContext(), backup.deviceId)
                        } catch(e: Exception) { Log.e(TAG, "Failed deleting device folder", e) }
                        
                        try {
                            com.sk.gallery.cloud.DriveBackupManager.deleteDeviceFolder(requireContext(), "${backup.deviceId}-private_safe")
                        } catch(e: Exception) { Log.e(TAG, "Failed deleting private safe folder", e) }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Selected backups deleted successfully.", Toast.LENGTH_SHORT).show()
                        binding.layoutDeleteList.removeAllViews()
                        binding.etDeletePin.text = null
                        binding.layoutDeletePin.visibility = View.GONE
                        binding.btnDeleteConfirm.visibility = View.GONE
                        binding.btnDeleteConfirm.isEnabled = true
                        binding.btnDeleteConfirm.text = "Delete Selected Backups"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Error deleting: ${e.message}", Toast.LENGTH_LONG).show()
                        binding.btnDeleteConfirm.isEnabled = true
                        binding.btnDeleteConfirm.text = "Delete Selected Backups"
                    }
                }
            }
        }
    }

    private fun startScanDeleteProcess() {
        binding.btnScanDelete.isEnabled = false
        binding.btnScanDelete.text = "Scanning Backups..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val backups = com.sk.gallery.cloud.DriveBackupManager.getAvailableBackups(requireContext(), com.sk.gallery.cloud.DriveBackupManager.ScanMode.DELETE_ALL)
                if (backups.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "No backups found in Google Drive.", Toast.LENGTH_LONG).show()
                        binding.btnScanDelete.isEnabled = true
                        binding.btnScanDelete.text = "Scan Google Drive for Backups"
                        binding.layoutDeleteList.removeAllViews()
                        binding.etDeletePin.text = null
                        binding.layoutDeletePin.visibility = View.GONE
                        binding.btnDeleteConfirm.visibility = View.GONE
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.btnScanDelete.isEnabled = true
                    binding.btnScanDelete.text = "Scan Google Drive for Backups"
                    setupDeleteList(backups)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan cloud for deletion", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error scanning backups: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.btnScanDelete.isEnabled = true
                    binding.btnScanDelete.text = "Scan Google Drive for Backups"
                }
            }
        }
    }

    private fun setupDeleteList(backups: List<com.sk.gallery.cloud.BackupInfo>) {
        binding.layoutDeleteList.removeAllViews()
        binding.etDeletePin.text = null
        binding.layoutDeletePin.visibility = View.GONE
        binding.btnDeleteConfirm.visibility = View.GONE

        for (backup in backups) {
            val checkBox = android.widget.CheckBox(requireContext()).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.text_primary))
                val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(backup.lastUpdatedTimestamp))
                val deviceLabel = if (backup.deviceName.equals(backup.deviceModel, ignoreCase = true)) {
                    backup.deviceName
                } else {
                    "${backup.deviceName} ${backup.deviceModel}"
                }
                text = "$deviceLabel ($dateStr) — ${backup.actualUploadedCount} files"
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 14f
                tag = backup
                setOnCheckedChangeListener { _, _ ->
                    updateDeleteConfirmButtonVisibility()
                }
            }
            binding.layoutDeleteList.addView(checkBox)
        }
    }

    private fun updateDeleteConfirmButtonVisibility() {
        var anyChecked = false
        for (i in 0 until binding.layoutDeleteList.childCount) {
            val child = binding.layoutDeleteList.getChildAt(i)
            if (child is android.widget.CheckBox && child.isChecked) {
                anyChecked = true
                break
            }
        }
        
        if (anyChecked) {
            binding.layoutDeletePin.visibility = View.VISIBLE
            binding.btnDeleteConfirm.visibility = View.VISIBLE
        } else {
            binding.layoutDeletePin.visibility = View.GONE
            binding.btnDeleteConfirm.visibility = View.GONE
        }
    }

    private fun startScanCloudProcess() {
        binding.btnScanCloud.isEnabled = false
        binding.btnScanCloud.text = "Scanning Backups..."
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val backups = com.sk.gallery.cloud.DriveBackupManager.getAvailableBackups(requireContext())
                if (backups.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "No backups found in Google Drive.", Toast.LENGTH_LONG).show()
                        binding.btnScanCloud.isEnabled = true
                        binding.btnScanCloud.text = "Scan Google Drive for Backup"
                        binding.layoutImportList.removeAllViews()
                        binding.layoutImportList.visibility = View.GONE
                        binding.layoutImportActions.visibility = View.GONE
                        binding.btnImportNow.visibility = View.GONE
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.btnScanCloud.isEnabled = true
                    binding.btnScanCloud.text = "Scan Google Drive for Backup"
                    setupDevicesImportList(backups)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan cloud", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error scanning backup: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.btnScanCloud.isEnabled = true
                    binding.btnScanCloud.text = "Scan Google Drive for Backup"
                }
            }
        }
    }

    private fun setupDevicesImportList(backups: List<com.sk.gallery.cloud.BackupInfo>) {
        binding.layoutImportList.removeAllViews()
        binding.layoutImportList.visibility = View.VISIBLE
        binding.layoutDeviceDropdown.visibility = View.GONE

        for (backup in backups) {
            val checkBox = android.widget.CheckBox(requireContext()).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.text_primary))
                val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(backup.lastUpdatedTimestamp))
                val deviceLabel = if (backup.deviceName.equals(backup.deviceModel, ignoreCase = true)) {
                    backup.deviceName
                } else {
                    "${backup.deviceName} ${backup.deviceModel}"
                }
                
                text = "$deviceLabel ($dateStr) — ${backup.actualUploadedCount} of ${backup.fileCount} files"
                
                setTextColor(resources.getColor(R.color.text_primary, null))
                tag = backup
                
                setOnCheckedChangeListener { _, _ ->
                    updateImportActionsVisibility()
                }
            }
            binding.layoutImportList.addView(checkBox)
        }
        updateImportActionsVisibility()
    }

    private fun updateImportActionsVisibility() {
        var anyChecked = false
        for (i in 0 until binding.layoutImportList.childCount) {
            val child = binding.layoutImportList.getChildAt(i)
            if (child is android.widget.CheckBox && child.isChecked) {
                anyChecked = true
                break
            }
        }
        if (anyChecked) {
            binding.layoutImportActions.visibility = View.VISIBLE
            binding.btnImportNow.visibility = View.VISIBLE
            binding.btnPauseImport.visibility = View.GONE
            binding.btnResumeImport.visibility = View.GONE
            binding.btnCancelImport.visibility = View.GONE
        } else {
            binding.layoutImportActions.visibility = View.GONE
            binding.btnImportNow.visibility = View.GONE
        }
    }

    private fun processSelectedBackups(backups: List<com.sk.gallery.cloud.BackupInfo>) {
        binding.btnScanCloud.isEnabled = false
        binding.btnScanCloud.text = "Preparing Restore..."
        binding.layoutImportList.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mediaRepository = MediaRepository.getInstance(requireContext())
                val localMedia = mediaRepository.mediaFlow.value.associateBy { it.hashId }
                val imports = mutableListOf<com.sk.gallery.data.db.ImportEntity>()

                for (backup in backups) {
                    // Fetch latest file names from Drive for this device folder
                    val remoteFileNames = com.sk.gallery.cloud.DriveBackupManager.getAllUploadedFileNames(requireContext(), backup.deviceFolderId)

                    for ((hashId, entry) in backup.manifest.entries) {
                        // Only import files that actually exist in the remote folder on Google Drive
                        if (!remoteFileNames.contains(hashId)) {
                            continue
                        }

                        val localFile = localMedia[hashId]
                        if (localFile != null) {
                            if (localFile.sha256Checksum == entry.sha256Checksum) {
                                val cleanDir = com.sk.gallery.util.FileUtils.getAlbumRelativePath(localFile.relativePath)
                                val expectedCleanPath = "$cleanDir/${localFile.fileName}".replace("\\", "/").trim('/')
                                val currentPath = localFile.relativePath.replace("\\", "/").trim('/')
                                if (currentPath == expectedCleanPath) {
                                    continue
                                }
                            }
                        }

                        imports.add(
                            com.sk.gallery.data.db.ImportEntity(
                                fileName = entry.fileName,
                                fileHash = entry.hashId,
                                expectedSizeBytes = entry.sizeBytes,
                                relativePath = entry.relativePath,
                                dateModified = entry.dateModified,
                                driveFileId = entry.cloudFileId,
                                deviceFolderId = backup.deviceFolderId
                            )
                        )
                    }
                }

                database.importDao().clearAll()
                if (imports.isNotEmpty()) {
                    database.importDao().insertAll(imports)
                }

                withContext(Dispatchers.Main) {
                    binding.btnScanCloud.isEnabled = true
                    binding.btnScanCloud.text = "Scan Google Drive for Backup"
                    binding.layoutImportList.isEnabled = true
                    
                    if (imports.isEmpty()) {
                        binding.btnImportNow.isEnabled = true
                        Toast.makeText(requireContext(), "All backed up files already exist on device.", Toast.LENGTH_LONG).show()
                    } else {
                        startImportProcess()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process selected backups", e)
                withContext(Dispatchers.Main) {
                    binding.btnImportNow.isEnabled = true
                    Toast.makeText(requireContext(), "Error preparing restore: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.btnScanCloud.isEnabled = true
                    binding.btnScanCloud.text = "Scan Google Drive for Backup"
                    binding.layoutImportList.isEnabled = true
                }
            }
        }
    }

    private fun startImportProcess() {
        val request = OneTimeWorkRequestBuilder<com.sk.gallery.worker.ImportWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            IMPORT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        
        binding.btnImportNow.visibility = View.GONE
        binding.btnResumeImport.visibility = View.GONE
        binding.btnPauseImport.visibility = View.VISIBLE
        binding.btnCancelImport.visibility = View.VISIBLE
    }

    private fun startExportProcess() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Check if queue is empty. If so, populate from already-loaded MediaRepository manifest.
            // We NEVER call scanMediaStore() here to avoid corrupting the manifest file.
            val currentPending = database.uploadDao().getByStatus(UploadEntity.STATUS_PENDING)
            val currentPaused = database.uploadDao().getByStatus(UploadEntity.STATUS_PAUSED)

            if (currentPending.isEmpty() && currentPaused.isEmpty()) {
                // Use the already-scanned manifest from the singleton repository
                val mediaRepository = MediaRepository.getInstance(requireContext())
                val manifest = mediaRepository.manifestFlow.value

                if (manifest == null || manifest.entries.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "No media found to back up.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val newUploads = manifest.entries.values.map { entry ->
                    val fullPath = "/storage/emulated/0/" + entry.relativePath
                    UploadEntity(
                        filePath = fullPath,
                        fileName = entry.fileName,
                        fileHash = entry.hashId,
                        fileSizeBytes = entry.sizeBytes
                    )
                }
                database.uploadDao().clearAll() // Prevent duplicate items if queue was finished or crashed
                database.uploadDao().insertAll(newUploads)
            } else {
                // Resume paused items
                currentPaused.forEach { database.uploadDao().updateStatus(it.id, UploadEntity.STATUS_PENDING) }
            }

            withContext(Dispatchers.Main) {
                val request = OneTimeWorkRequestBuilder<ExportWorker>()
                    .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(requireContext()).enqueueUniqueWork(
                    EXPORT_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                
                binding.btnExportNow.visibility = View.GONE
                binding.btnResumeBackup.visibility = View.GONE
                binding.btnPauseBackup.visibility = View.VISIBLE
                binding.btnCancelBackup.visibility = View.VISIBLE
                binding.layoutExportProgress.visibility = View.VISIBLE
                binding.bannerQuotaWarning.visibility = View.GONE
            }
        }
    }

    private fun observeDatabaseProgress() {
        database.uploadDao().getAllLiveData().observe(viewLifecycleOwner) { uploads ->
            if (uploads.isEmpty()) return@observe

            val completed = uploads.count { it.status == UploadEntity.STATUS_COMPLETED }
            val failed = uploads.count { it.status == UploadEntity.STATUS_FAILED }
            val paused = uploads.count { it.status == UploadEntity.STATUS_PAUSED }
            val total = uploads.size
            
            if (total > 0) {
                binding.layoutExportProgress.visibility = View.VISIBLE
                val progress = ((completed.toFloat() / total) * 100).toInt()
                binding.progressBarUpload.progress = progress
                binding.tvUploadPercentage.text = "$progress%"
                
                binding.tvUploadStatus.text = "Uploaded $completed of $total"
                
                if (paused > 0) {
                    isPausingExport = false
                    binding.tvUploadStatus.text = "Paused ($completed of $total)"
                    binding.btnExportNow.visibility = View.GONE
                    binding.btnPauseBackup.visibility = View.GONE
                    binding.btnResumeBackup.visibility = View.VISIBLE
                    binding.btnCancelBackup.visibility = View.VISIBLE
                } else if (completed + failed == total) {
                    isPausingExport = false
                    binding.tvUploadStatus.text = "Backup Complete"
                    binding.btnExportNow.visibility = View.VISIBLE
                    binding.btnPauseBackup.visibility = View.GONE
                    binding.btnResumeBackup.visibility = View.GONE
                    binding.btnCancelBackup.visibility = View.GONE
                    binding.btnExportNow.text = "Done"
                    binding.btnExportNow.isEnabled = true
                } else {
                    // Actively running
                    binding.btnExportNow.visibility = View.GONE
                    binding.btnResumeBackup.visibility = View.GONE
                    binding.btnCancelBackup.visibility = View.VISIBLE
                    
                    if (isPausingExport) {
                        binding.tvUploadStatus.text = "Pausing backup..."
                        binding.btnPauseBackup.visibility = View.VISIBLE
                        binding.btnPauseBackup.isEnabled = false
                        binding.btnPauseBackup.text = "Pausing..."
                    } else {
                        binding.tvUploadStatus.text = "Uploading $completed of $total"
                        binding.btnPauseBackup.visibility = View.VISIBLE
                        binding.btnPauseBackup.isEnabled = true
                        binding.btnPauseBackup.text = "Pause"
                    }
                }
                updateDisconnectButtonState()
            }
        }
    }

    private fun observeImportDatabaseProgress() {
        database.importDao().getAllLiveData().observe(viewLifecycleOwner) { imports ->
            if (imports.isEmpty()) {
                binding.layoutImportProgress.visibility = View.GONE
                binding.layoutImportActions.visibility = View.GONE
                binding.btnScanCloud.visibility = View.VISIBLE
                binding.layoutImportList.visibility = View.GONE
                return@observe
            }
            binding.btnScanCloud.visibility = View.GONE

            val completed = imports.count { it.status == com.sk.gallery.data.db.ImportEntity.STATUS_COMPLETED }
            val failed = imports.count { it.status == com.sk.gallery.data.db.ImportEntity.STATUS_FAILED }
            val paused = imports.count { it.status == com.sk.gallery.data.db.ImportEntity.STATUS_PAUSED }
            val total = imports.size
            
            if (total > 0) {
                binding.layoutImportProgress.visibility = View.VISIBLE
                binding.layoutImportActions.visibility = View.VISIBLE
                
                val progress = ((completed.toFloat() / total) * 100).toInt()
                binding.progressBarImport.progress = progress
                binding.tvImportPercentage.text = "$progress%"
                
                if (paused > 0) {
                    isPausingImport = false
                    binding.tvImportStatus.text = "Paused ($completed of $total)"
                    binding.btnImportNow.visibility = View.GONE
                    binding.btnPauseImport.visibility = View.GONE
                    binding.btnResumeImport.visibility = View.VISIBLE
                    binding.btnCancelImport.visibility = View.VISIBLE
                } else if (completed + failed == total) {
                    isPausingImport = false
                    binding.tvImportStatus.text = "Restore Complete"
                    binding.btnImportNow.visibility = View.VISIBLE
                    binding.btnPauseImport.visibility = View.GONE
                    binding.btnResumeImport.visibility = View.GONE
                    binding.btnCancelImport.visibility = View.GONE
                    binding.btnImportNow.text = "Done"
                    binding.btnImportNow.isEnabled = true
                } else {
                    // Actively running
                    binding.btnImportNow.visibility = View.GONE
                    binding.btnCancelImport.visibility = View.VISIBLE
                    
                    if (isPausingImport) {
                        binding.tvImportStatus.text = "Pausing restore..."
                        binding.btnPauseImport.visibility = View.VISIBLE
                        binding.btnPauseImport.isEnabled = false
                        binding.btnPauseImport.text = "Pausing..."
                        binding.btnResumeImport.visibility = View.GONE
                    } else {
                        binding.tvImportStatus.text = "Restoring $completed of $total"
                        binding.btnPauseImport.visibility = View.VISIBLE
                        binding.btnPauseImport.isEnabled = true
                        binding.btnPauseImport.text = "Pause"
                        binding.btnResumeImport.visibility = View.GONE
                    }
                }
                updateDisconnectButtonState()
            }
        }
    }

    private fun updateDisconnectButtonState() {
        val exportActive = binding.btnPauseBackup.visibility == View.VISIBLE
        val importActive = binding.btnPauseImport.visibility == View.VISIBLE
        binding.btnDisconnectDrive.isEnabled = !exportActive && !importActive
        if (!binding.btnDisconnectDrive.isEnabled) {
            binding.btnDisconnectDrive.alpha = 0.5f
        } else {
            binding.btnDisconnectDrive.alpha = 1.0f
        }
    }

    private fun observeWorkManager() {
        WorkManager.getInstance(requireContext())
            .getWorkInfosForUniqueWorkLiveData(EXPORT_WORK_NAME)
            .observe(viewLifecycleOwner) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe
                val latestWork = workInfos.first()

                when (latestWork.state) {
                    WorkInfo.State.FAILED -> {
                        val status = latestWork.outputData.getString(ExportWorker.KEY_STATUS)
                        if (status == "QUOTA_EXCEEDED") {
                            binding.bannerQuotaWarning.visibility = View.VISIBLE
                            binding.btnExportNow.visibility = View.GONE
                            binding.btnPauseBackup.visibility = View.GONE
                            binding.btnResumeBackup.visibility = View.VISIBLE
                            binding.btnCancelBackup.visibility = View.VISIBLE
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        binding.bannerQuotaWarning.visibility = View.GONE
                    }
                    else -> {}
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
