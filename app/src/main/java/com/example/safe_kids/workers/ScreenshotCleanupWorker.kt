package com.example.safe_kids.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class ScreenshotCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ScreenshotCleanup"
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val request = androidx.work.PeriodicWorkRequestBuilder<ScreenshotCleanupWorker>(
                12, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ScreenshotCleanup",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running scheduled screenshot cleanup...")
        val childId = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()

        return try {
            val firestore = FirebaseFirestore.getInstance()
            val storage = FirebaseStorage.getInstance()

            val docRef = firestore.collection("screenshots")
                .document(childId)
                .collection("latest")
                .document("result")

            val snapshot = docRef.get().await()

            if (snapshot.exists()) {
                val timestamp = snapshot.getLong("timestamp") ?: 0L
                val age = System.currentTimeMillis() - timestamp

                if (age > MAX_AGE_MS) {
                    Log.d(TAG, "Screenshot is older than 24h. Deleting to conserve storage.")
                    
                    // 1. Delete from Storage
                    val imageRef = storage.reference.child("screenshots/$childId/latest.jpg")
                    try {
                        imageRef.delete().await()
                    } catch (e: Exception) {
                        Log.w(TAG, "File might already be deleted or not found", e)
                    }

                    // 2. Delete from Firestore
                    docRef.delete().await()
                    Log.d(TAG, "Cleanup complete.")
                } else {
                    Log.d(TAG, "Screenshot is ${age / 1000 / 60} minutes old. Keeping it.")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during screenshot cleanup", e)
            Result.retry()
        }
    }
}
