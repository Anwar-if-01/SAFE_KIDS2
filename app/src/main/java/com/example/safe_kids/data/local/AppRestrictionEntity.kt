package com.example.safe_kids.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RestrictionType {
    BLOCKED,
    ALWAYS_ALLOWED
}

@Entity(tableName = "app_restrictions")
data class AppRestrictionEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val type: RestrictionType,
    val lastUpdated: Long = System.currentTimeMillis()
)
