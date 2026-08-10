package com.sk.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [UploadEntity::class, ImportEntity::class], version = 4, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {

    abstract fun uploadDao(): UploadDao
    abstract fun importDao(): ImportDao

    companion object {
        @Volatile
        private var INSTANCE: UploadDatabase? = null

        fun getDatabase(context: Context): UploadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UploadDatabase::class.java,
                    "gallery_upload_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
