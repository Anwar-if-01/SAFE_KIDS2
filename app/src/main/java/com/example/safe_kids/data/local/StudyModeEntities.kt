package com.example.safe_kids.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "study_mode_config")
data class StudyModeConfigEntity(
    @PrimaryKey val id: Int = 1, // Only one config per device
    val isEnabled: Boolean,
    val startTime: String,
    val endTime: String,
    val activeDays: String, // Comma separated IDs (1,2,3,4,5)
    val allowedApps: String, // Comma separated package names
    val lastUpdated: Long
)

@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey val id: String,
    val date: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val appsUsed: String, // Comma separated
    val isSynced: Boolean = false
)

@Dao
interface StudyModeDao {
    @Query("SELECT * FROM study_mode_config WHERE id = 1")
    fun getConfig(): Flow<StudyModeConfigEntity?>

    @Query("SELECT * FROM study_mode_config WHERE id = 1")
    suspend fun getConfigSync(): StudyModeConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: StudyModeConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: StudySessionEntity)

    @Query("SELECT * FROM study_sessions WHERE isSynced = 0")
    suspend fun getUnsyncedSessions(): List<StudySessionEntity>

    @Query("UPDATE study_sessions SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSessionsSynced(ids: List<String>)
}
