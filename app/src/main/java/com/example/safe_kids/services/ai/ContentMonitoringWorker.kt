package com.example.safe_kids.services.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentMonitoringWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val aiService = AIContentAnalysisServiceImpl(context)
    private val PREFS_NAME = "ai_monitoring_prefs"
    private val KEY_LAST_SCANNED_ID = "last_scanned_id"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val sharedPrefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastScannedId = sharedPrefs.getLong(KEY_LAST_SCANNED_ID, -1L)

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DATE_ADDED
            )

            val selection = if (lastScannedId != -1L) {
                "${MediaStore.Images.Media._ID} > ?"
            } else null

            val selectionArgs = if (lastScannedId != -1L) {
                arrayOf(lastScannedId.toString())
            } else null

            val sortOrder = "${MediaStore.Images.Media._ID} ASC"

            val query = applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            var latestId = lastScannedId

            query?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(pathColumn)

                    Log.d("AI_MONITOR", "Scanning image: $path")
                    
                    try {
                        val bitmap = BitmapFactory.decodeFile(path)
                        if (bitmap != null) {
                            aiService.detectInappropriateContent(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e("AI_MONITOR", "Failed to process image $path", e)
                    }

                    latestId = id
                }
            }

            sharedPrefs.edit().putLong(KEY_LAST_SCANNED_ID, latestId).apply()
            Result.success()
        } catch (e: Exception) {
            Log.e("AI_MONITOR", "Error in ContentMonitoringWorker", e)
            Result.retry()
        }
    }
}
