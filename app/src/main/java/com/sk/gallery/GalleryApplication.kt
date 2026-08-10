package com.sk.gallery

import android.app.Application
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import com.sk.gallery.data.trash.TrashCleanupWorker

class GalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val workRequest = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "TrashCleanupWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
