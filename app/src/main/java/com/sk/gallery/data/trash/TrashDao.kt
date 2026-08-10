package com.sk.gallery.data.trash

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash_entries ORDER BY deletedAt DESC")
    fun getAllTrashFlow(): Flow<List<TrashEntry>>

    @Query("SELECT * FROM trash_entries")
    suspend fun getAllTrashSync(): List<TrashEntry>

    @Query("SELECT * FROM trash_entries WHERE deletedAt <= :cutoffTime")
    suspend fun getExpiredTrash(cutoffTime: Long): List<TrashEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashEntry(entry: TrashEntry)

    @Query("DELETE FROM trash_entries WHERE originalHashId = :hashId")
    suspend fun deleteTrashEntry(hashId: String)
    
    @Query("DELETE FROM trash_entries")
    suspend fun clearAllTrash()
}
