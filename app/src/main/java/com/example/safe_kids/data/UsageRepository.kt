package com.example.safe_kids.data

import android.content.Context
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.local.AppDatabase
import com.example.safe_kids.data.local.AppUsageEntity
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class UsageRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val usageDao = database.appUsageDao()
    private val firestore = FirebaseFirestore.getInstance()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun getCurrentDate(): String = dateFormatter.format(Date())

    fun getUsageForToday(): Flow<List<AppUsageEntity>> {
        return usageDao.getUsageForDate(getCurrentDate())
    }

    suspend fun getUsageForTodaySync(): List<AppUsageEntity> {
        return usageDao.getUsageForDateSync(getCurrentDate())
    }

    suspend fun recordUsage(packageName: String, durationMillis: Long) {
        val date = getCurrentDate()
        val existing = usageDao.getUsageByPackage(packageName, date)
        if (existing == null) {
            usageDao.insertOrUpdate(AppUsageEntity(
                packageName = packageName,
                date = date,
                durationMillis = durationMillis,
                isSynced = false
            ))
        } else {
            usageDao.incrementUsage(packageName, date, durationMillis)
        }
    }

    suspend fun getWeeklyUsageForPackage(packageName: String): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -6) // include today and 6 prior days => 7 days
        val startDate = dateFormatter.format(calendar.time)
        val list = usageDao.getUsageSinceDate(packageName, startDate)
        return list.sumOf { it.durationMillis }
    }

    suspend fun syncUsageToFirestore(childId: String): Result<Unit> {
        return try {
            val unsynced = usageDao.getUnsyncedUsage()
            if (unsynced.isEmpty()) return Result.success(Unit)

            val batch = firestore.batch()
            val date = getCurrentDate()
            
            // Group by date
            val usageMap = unsynced.filter { it.date == date }.associate { 
                it.packageName to it.durationMillis 
            }

            if (usageMap.isNotEmpty()) {
                val totalDailyMs = usageMap.values.sum()
                
                // Write to children/{childId}/usageStats
                usageMap.forEach { (packageName, duration) ->
                    val appStatsRef = firestore.collection("children")
                        .document(childId)
                        .collection("usageStats")
                        .document(packageName)
                    
                    batch.set(appStatsRef, mapOf(
                        "packageName" to packageName,
                        "appName" to (try {
                            val pm = context.packageManager
                            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                        } catch (e: Exception) {
                            packageName.split(".").last().replaceFirstChar { it.uppercase() }
                        }),
                        "dailyUsageMs" to duration,
                        "date" to date,
                        "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ), com.google.firebase.firestore.SetOptions.merge())
                }
                
                // Write aggregate to children/{childId}/usageStats/_summary
                val summaryRef = firestore.collection("children")
                    .document(childId)
                    .collection("usageStats")
                    .document("_summary")
                
                batch.set(summaryRef, mapOf(
                    "totalDailyMs" to totalDailyMs,
                    "appCount" to usageMap.size,
                    "date" to date,
                    "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
                
                // Write to usage_history for weekly aggregation
                val usageHistoryRef = firestore.collection("usage_history")
                    .document(childId)
                    .collection("days")
                    .document(date)
                
                batch.set(usageHistoryRef, mapOf(
                    "totalDuration" to totalDailyMs,
                    "usage" to usageMap,
                    "date" to date,
                    "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
                
                // Update lastActive on user profile
                batch.update(firestore.collection(com.example.safe_kids.auth.AuthConfig.USERS_COLLECTION).document(childId), 
                    "lastActive", System.currentTimeMillis())
            }

            batch.commit().await()
            
            // Mark as synced in local DB
            usageDao.markAsSynced(unsynced.map { it.id })
            
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("UsageRepository", "Sync failed", e)
            Result.failure(e)
        }
    }
    
    suspend fun clearOldData() {
        // Keep last 7 days of local history
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val cutoffDate = dateFormatter.format(calendar.time)
        usageDao.clearOldUsage(cutoffDate)
    }
}
