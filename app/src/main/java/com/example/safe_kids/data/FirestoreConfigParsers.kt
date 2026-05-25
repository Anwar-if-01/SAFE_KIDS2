package com.example.safe_kids.data

import com.google.firebase.firestore.DocumentSnapshot

private fun DocumentSnapshot.getBooleanCompat(vararg keys: String): Boolean? {
    for (k in keys) {
        val v = this.get(k)
        if (v is Boolean) return v
    }
    return null
}

private fun DocumentSnapshot.getStringCompat(vararg keys: String): String? {
    for (k in keys) {
        val v = this.getString(k)
        if (v != null) return v
    }
    return null
}

private fun DocumentSnapshot.getLongCompat(vararg keys: String): Long? {
    for (k in keys) {
        val v = this.get(k)
        if (v is Long) return v
        if (v is Number) return v.toLong()
    }
    return null
}

private fun <T> DocumentSnapshot.getListCompat(key: String): List<T>? {
    val raw = this.get(key) as? List<*>
    if (raw == null) return null
    @Suppress("UNCHECKED_CAST")
    return raw.mapNotNull {
        when (it) {
            is Number -> (it.toInt() as T)
            is String -> (it as T)
            is Int -> (it as T)
            is Long -> (it.toInt() as T)
            else -> null
        }
    }
}

fun DocumentSnapshot.toSleepModeConfigCompat(): SleepModeConfig {
    val isEnabled = getBooleanCompat("isEnabled", "enabled") ?: false
    val startTime = getStringCompat("startTime", "start") ?: "21:00"
    val endTime = getStringCompat("endTime", "end") ?: "07:00"
    val activeDays = getListCompat<Int>("activeDays") ?: (1..7).toList()
    val suppressNotifications = getBooleanCompat("suppressNotifications", "suppress_notifications") ?: true
    val lastUpdated = getLongCompat("lastUpdated", "updatedAt", "last_updated") ?: System.currentTimeMillis()
    return SleepModeConfig(
        isEnabled = isEnabled,
        startTime = startTime,
        endTime = endTime,
        activeDays = activeDays,
        suppressNotifications = suppressNotifications,
        lastUpdated = lastUpdated
    )
}

fun DocumentSnapshot.toStudyModeConfigCompat(): StudyModeConfig {
    val isEnabled = getBooleanCompat("isEnabled", "enabled") ?: false
    val startTime = getStringCompat("startTime", "start") ?: "09:00"
    val endTime = getStringCompat("endTime", "end") ?: "10:00"
    val activeDays = getListCompat<Int>("activeDays") ?: listOf(1,2,3,4,5)
    val allowedAppsRaw = this.get("allowedApps") as? List<*>
    val allowedApps = allowedAppsRaw?.mapNotNull { it as? String } ?: emptyList()
    val lastUpdated = getLongCompat("lastUpdated", "updatedAt", "last_updated") ?: System.currentTimeMillis()
    return StudyModeConfig(
        isEnabled = isEnabled,
        startTime = startTime,
        endTime = endTime,
        activeDays = activeDays,
        allowedApps = allowedApps,
        lastUpdated = lastUpdated
    )
}
