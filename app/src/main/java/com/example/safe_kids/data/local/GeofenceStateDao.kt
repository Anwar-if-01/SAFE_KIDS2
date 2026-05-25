package com.example.safe_kids.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeofenceStateDao {
    @Query("SELECT * FROM geofence_states WHERE zoneId = :zoneId")
    suspend fun getState(zoneId: String): GeofenceStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateState(state: GeofenceStateEntity)

    @Query("DELETE FROM geofence_states WHERE zoneId NOT IN (:activeZoneIds)")
    suspend fun clearInactiveStates(activeZoneIds: List<String>)
}
