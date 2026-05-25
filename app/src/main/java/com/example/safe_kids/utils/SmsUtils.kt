package com.example.safe_kids.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object SmsUtils {
    private const val TAG = "SmsUtils"

    suspend fun sendEmergencySms(context: Context, childId: String, location: String?) {
        try {
            // Get parent phone number from Firestore
            val db = FirebaseFirestore.getInstance()
            val childDoc = db.collection("children").document(childId).get().await()
            
            val parentPhone = childDoc.getString("parentPhone") ?: run {
                Log.e(TAG, "No parent phone number found for child $childId")
                return
            }

            // Format message
            val message = "SOS ALERT: Your child needs help. " +
                    (location?.let { "Location: $it" } ?: "Location unknown")

            // Send SMS - use proper API version check for SmsManager
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val sentPI = PendingIntent.getBroadcast(
                context, 
                0, 
                Intent("SMS_SENT"), 
                PendingIntent.FLAG_IMMUTABLE
            )
            
            smsManager.sendTextMessage(
                parentPhone,
                null, // scAddress
                message,
                sentPI,
                null // deliveryIntent
            )
            
            Log.d(TAG, "Emergency SMS sent to $parentPhone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send emergency SMS", e)
        }
    }
}
