package com.example.safe_kids.utils

import com.google.firebase.database.FirebaseDatabase
import android.util.Log

object FirebaseUtils {
    private const val TAG = "FirebaseUtils"
    private const val REGIONAL_DATABASE_URL = "https://safe-kids-cf8a6-default-rtdb.europe-west1.firebasedatabase.app"
    
    /**
     * Get Firebase Realtime Database instance with the correct regional URL.
     * Falls back to default instance if regional instance fails.
     */
    fun getRealtimeDatabase(): FirebaseDatabase {
        return try {
            FirebaseDatabase.getInstance(REGIONAL_DATABASE_URL)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get regional RTDB instance, using default", e)
            FirebaseDatabase.getInstance()
        }
    }
}
