package com.example.kenza.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.kenza.database.models.CleanedEmail
import com.example.kenza.database.dao.CleanedEmailDao

@Database(entities = [CleanedEmail::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cleanedEmailDao(): CleanedEmailDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kenza_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}