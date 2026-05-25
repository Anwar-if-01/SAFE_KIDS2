package com.example.safe_kids.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException

object FirestoreQueryUtils {
    private const val TAG = "FirestoreQueryUtils"

    fun userMessage(error: Exception?): String {
        if (error == null) return "Unable to load data. Please try again."
        if (error is FirebaseFirestoreException) {
            when (error.code) {
                FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
                    return "Database index is still building. Wait a few minutes and try again."
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    return "Permission denied. Please sign in again."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    return "Network unavailable. Check your connection."
                else -> Unit
            }
        }
        return error.message?.takeIf { it.isNotBlank() }
            ?: "Unable to load data. Please try again."
    }

    fun logQueryError(source: String, error: Exception?) {
        if (error != null) {
            Log.e(TAG, "$source failed: ${error.message}", error)
        }
    }
}
