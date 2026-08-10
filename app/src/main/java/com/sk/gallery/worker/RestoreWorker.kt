package com.sk.gallery.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sk.gallery.auth.GoogleSignInManager
import com.sk.gallery.cloud.DriveVaultManager
import com.sk.gallery.data.MediaStoreScanner
import com.sk.gallery.restore.ReconciliationEngine
import com.sk.gallery.restore.RestorationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val account = GoogleSignInManager.getLastSignedInAccount(applicationContext)
            ?: return@withContext Result.failure()

        try {
            val driveService = GoogleSignInManager.getDriveService(applicationContext, account)
            val vaultManager = DriveVaultManager(driveService)
            val scanner = MediaStoreScanner(applicationContext)

            // Step 1: Download cloud manifest
            val cloudManifest = vaultManager.downloadManifest()
                ?: return@withContext Result.failure()

            // Step 2: Scan local media store
            val localManifest = scanner.scanMediaStore()

            // Step 3: Reconcile indices to identify ghost cards
            val reconciledManifest = ReconciliationEngine.reconcileIndices(localManifest, cloudManifest)

            // Step 4: Restore missing local files
            val restorationManager = RestorationManager(applicationContext, driveService)
            val updatedEntries = reconciledManifest.entries.toMutableMap()
            var restoredCount = 0

            for ((hashId, entry) in reconciledManifest.entries) {
                if (entry.isMissingLocally) {
                    try {
                        val success = restorationManager.restoreFile(entry)
                        if (success) {
                            updatedEntries[hashId] = entry.copy(isMissingLocally = false)
                            restoredCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Step 5: Save updated local manifest
            scanner.updateManifestLocally { latestManifest ->
                if (latestManifest != null) {
                    val newEntries = latestManifest.entries.toMutableMap()
                    for ((hashId, entry) in updatedEntries) {
                        newEntries[hashId] = entry
                    }
                    latestManifest.copy(
                        lastUpdatedTimestamp = System.currentTimeMillis(),
                        entries = newEntries
                    )
                } else {
                    reconciledManifest.copy(
                        lastUpdatedTimestamp = System.currentTimeMillis(),
                        entries = updatedEntries
                    )
                }
            }

            return@withContext Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.retry()
        }
    }
}
