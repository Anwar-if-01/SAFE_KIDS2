package com.example.safe_kids.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [
    AppUsageEntity::class, 
    GeofenceStateEntity::class, 
    AppRestrictionEntity::class,
    StudyModeConfigEntity::class,
    StudySessionEntity::class,
    SleepModeConfigEntity::class,
    SleepSessionEntity::class
], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appUsageDao(): AppUsageDao
    abstract fun geofenceStateDao(): GeofenceStateDao
    abstract fun appRestrictionDao(): AppRestrictionDao
    abstract fun studyModeDao(): StudyModeDao
    abstract fun sleepModeDao(): SleepModeDao



    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "safe_kids_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
