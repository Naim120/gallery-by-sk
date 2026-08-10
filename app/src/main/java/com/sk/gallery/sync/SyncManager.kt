package com.sk.gallery.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.sk.gallery.worker.SyncWorker
import java.util.concurrent.TimeUnit

object SyncManager {

    const val ONE_TIME_SYNC_WORK_NAME = "gallery_by_sk_one_time_sync"
    const val PERIODIC_SYNC_WORK_NAME = "gallery_by_sk_periodic_sync"
    const val SYNC_WORK_TAG = "tag_sync_work"

    fun triggerOneTimeSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .addTag(SYNC_WORK_TAG)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    fun schedulePeriodicSync(context: Context, intervalHours: Long = 6) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours, TimeUnit.HOURS)
            .addTag(SYNC_WORK_TAG)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest
        )
    }

    fun cancelAllSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
    }
}
