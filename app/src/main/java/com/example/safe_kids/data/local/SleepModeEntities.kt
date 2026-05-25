package com.example.safe_kids.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sleep_mode_configs")
data class SleepModeConfigEntity(
    @PrimaryKey val id: String = "singleton_config",
    val isEnabled: Boolean,
    val startTime: String,
    val endTime: String,
    val activeDays: String, // Comma-separated ints
    val suppressNotifications: Boolean,
    val lastUpdated: Long
)

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey val id: String,
    val date: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val emergencyUsageMinutes: Int
)

@Dao
interface SleepModeDao {
    @Query("SELECT * FROM sleep_mode_configs WHERE id = 'singleton_config'")
    fun getConfig(): Flow<SleepModeConfigEntity?>

    @Query("SELECT * FROM sleep_mode_configs WHERE id = 'singleton_config'")
    suspend fun getConfigSync(): SleepModeConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: SleepModeConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SleepSessionEntity)

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<SleepSessionEntity>>
}
