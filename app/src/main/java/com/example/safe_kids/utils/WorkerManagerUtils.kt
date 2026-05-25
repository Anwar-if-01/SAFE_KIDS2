package com.example.safe_kids.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.safe_kids.services.DeviceStatusWorker
import java.util.concurrent.TimeUnit

object WorkerManagerUtils {
    private const val DEVICE_STATUS_WORK_NAME = "device_status_sync_work"

    fun scheduleDeviceStatusSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Schedule to run every 15 minutes (minimum allowed by WorkManager)
        val syncRequest = PeriodicWorkRequestBuilder<DeviceStatusWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DEVICE_STATUS_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE, // Replace existing to ensure it runs
            syncRequest
        )
    }

    fun cancelDeviceStatusSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DEVICE_STATUS_WORK_NAME)
    }
}
