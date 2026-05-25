package com.example.safe_kids.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {
    @Query("SELECT * FROM app_usage WHERE date = :date")
    fun getUsageForDate(date: String): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage WHERE date = :date")
    suspend fun getUsageForDateSync(date: String): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND date = :date LIMIT 1")
    suspend fun getUsageByPackage(packageName: String, date: String): AppUsageEntity?

    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND date >= :startDate")
    suspend fun getUsageSinceDate(packageName: String, startDate: String): List<AppUsageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(usage: AppUsageEntity)

    @Query("UPDATE app_usage SET durationMillis = durationMillis + :deltaMillis, lastUpdated = :now, isSynced = 0 WHERE packageName = :packageName AND date = :date")
    suspend fun incrementUsage(packageName: String, date: String, deltaMillis: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM app_usage WHERE isSynced = 0")
    suspend fun getUnsyncedUsage(): List<AppUsageEntity>

    @Query("UPDATE app_usage SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM app_usage WHERE date < :date")
    suspend fun clearOldUsage(date: String)
}
