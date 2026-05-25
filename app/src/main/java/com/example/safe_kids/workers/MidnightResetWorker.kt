package com.example.safe_kids.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.safe_kids.data.UsageRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MidnightResetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val childId = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
            
            Log.d("MidnightResetWorker", "Running midnight reset for $childId")
            
            // 1. Clear old Room DB usage
            val usageRepository = UsageRepository(applicationContext)
            usageRepository.clearOldData()

            // 2. Clear extended screen time from Firestore
            val firestore = FirebaseFirestore.getInstance()
            
            try {
                firestore.collection("screen_time_limits").document(childId)
                    .update("extendedMinutes", 0)
                    .await()
            } catch (e: Exception) {
                // Document might not exist, which is fine
            }

            // 3. Weekly reset: if today is Monday, zero weeklyUsageMs documents under children/{childId}/usageStats
            val cal = Calendar.getInstance()
            val todayDow = cal.get(Calendar.DAY_OF_WEEK)
            // Calendar.MONDAY == 2
            if (todayDow == Calendar.MONDAY) {
                try {
                    val usageStatsRef = firestore.collection("children").document(childId).collection("usageStats")
                    val snapshots = usageStatsRef.get().await()
                    val batch = firestore.batch()
                    for (doc in snapshots.documents) {
                        batch.update(doc.reference, mapOf("weeklyUsageMs" to 0, "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                    }
                    if (snapshots.documents.isNotEmpty()) batch.commit().await()
                } catch (e: Exception) {
                    Log.e("MidnightResetWorker", "Failed weekly reset", e)
                }
            }
                
            return Result.success()
        } catch (e: Exception) {
            Log.e("MidnightResetWorker", "Reset failed", e)
            return Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "MidnightResetWorker"

        fun schedule(context: Context) {
            val now = Calendar.getInstance()
            val midnight = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            
            val initialDelay = midnight.timeInMillis - now.timeInMillis
            Log.d("MidnightResetWorker", "Scheduling reset in ${initialDelay / 60000} minutes")
            
            val workRequest = PeriodicWorkRequestBuilder<MidnightResetWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
