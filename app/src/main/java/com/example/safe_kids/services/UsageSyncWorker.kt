package com.example.safe_kids.services

import android.content.Context
import androidx.work.*
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.data.UsageRepository
import java.util.concurrent.TimeUnit

class UsageSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val authManager = AuthManagerImpl(applicationContext)
        val usageRepository = UsageRepository(applicationContext)
        
        // Ensure we are initialized to get the current user ID
        authManager.initialize()
        val userId = authManager.getCurrentUser()?.id ?: return Result.retry()

        return try {
            val syncResult = usageRepository.syncUsageToFirestore(userId)
            if (syncResult.isSuccess) {
                usageRepository.clearOldData()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val SYNC_WORK_NAME = "usage_sync_work"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<UsageSyncWorker>(
                15, TimeUnit.MINUTES // Minimum periodic interval is 15 minutes
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Keep existing to avoid resetting timer
                syncRequest
            )
        }
        
        fun runOnce(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<UsageSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(syncRequest)
        }
    }
}
