package com.example.safe_kids.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRestrictionDao {
    @Query("SELECT * FROM app_restrictions")
    fun getAllRestrictionsFlow(): Flow<List<AppRestrictionEntity>>

    @Query("SELECT * FROM app_restrictions WHERE type = :type")
    fun getRestrictionsByTypeFlow(type: RestrictionType): Flow<List<AppRestrictionEntity>>

    @Query("SELECT * FROM app_restrictions WHERE type = 'BLOCKED'")
    suspend fun getBlockedAppsSync(): List<AppRestrictionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(restrictions: List<AppRestrictionEntity>)

    @Query("DELETE FROM app_restrictions")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(restrictions: List<AppRestrictionEntity>) {
        clearAll()
        insertAll(restrictions)
    }
}
