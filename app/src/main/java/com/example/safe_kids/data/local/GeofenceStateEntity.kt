package com.example.safe_kids.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofence_states")
data class GeofenceStateEntity(
    @PrimaryKey val zoneId: String,
    val isInside: Boolean,
    val lastUpdateTime: Long = System.currentTimeMillis()
)
