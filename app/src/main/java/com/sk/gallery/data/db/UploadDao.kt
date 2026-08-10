package com.sk.gallery.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<UploadEntity>)

    @Update
    suspend fun update(entity: UploadEntity)

    @Query("SELECT * FROM upload_queue WHERE status IN ('PENDING', 'UPLOADING', 'PAUSED') ORDER BY id ASC")
    suspend fun getPendingOrUploading(): List<UploadEntity>

    @Query("SELECT * FROM upload_queue WHERE status = :status ORDER BY id ASC")
    suspend fun getByStatus(status: String): List<UploadEntity>

    @Query("SELECT COUNT(*) FROM upload_queue")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status = :status")
    suspend fun getCountByStatus(status: String): Int

    @Query("SELECT * FROM upload_queue WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): UploadEntity?

    @Query("SELECT * FROM upload_queue ORDER BY id DESC")
    fun getAllFlow(): Flow<List<UploadEntity>>

    @Query("SELECT * FROM upload_queue ORDER BY id DESC")
    fun getAllLiveData(): LiveData<List<UploadEntity>>

    @Query("UPDATE upload_queue SET status = :status, sessionUri = :sessionUri, bytesUploaded = :bytesUploaded, lastUpdatedTimestamp = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, sessionUri: String?, bytesUploaded: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM upload_queue WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM upload_queue")
    suspend fun clearAll()
}
