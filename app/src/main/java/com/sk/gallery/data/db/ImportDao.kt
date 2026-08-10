package com.sk.gallery.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ImportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(imports: List<ImportEntity>)

    @Query("SELECT * FROM import_queue WHERE status = :status")
    suspend fun getByStatus(status: String): List<ImportEntity>

    @Query("SELECT * FROM import_queue WHERE status IN ('PENDING', 'DOWNLOADING', 'PAUSED')")
    suspend fun getPendingOrDownloading(): List<ImportEntity>

    @Query("UPDATE import_queue SET status = :status, lastUpdatedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM import_queue ORDER BY id ASC")
    fun getAllLiveData(): LiveData<List<ImportEntity>>

    @Query("DELETE FROM import_queue")
    suspend fun clearAll()
}
