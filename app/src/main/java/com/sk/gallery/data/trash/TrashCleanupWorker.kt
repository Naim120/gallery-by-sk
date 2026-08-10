package com.sk.gallery.data.trash

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TrashCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            TrashManager.cleanupExpiredTrash(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
