package com.example.safe_kids.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val date: String, // Format: yyyy-MM-dd
    val durationMillis: Long,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
