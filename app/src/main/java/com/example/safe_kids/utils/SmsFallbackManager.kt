package com.example.safe_kids.utils

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/**
 * Handles emergency SMS fallback when data connection is unavailable.
 */
object SmsFallbackManager {
    private const val TAG = "SmsFallbackManager"

    /**
     * Sends an SOS SMS to the provided phone number.
     */
    fun sendSosSms(
        context: Context,
        parentPhoneNumber: String,
        childName: String,
        latitude: Double,
        longitude: Double,
        batteryPercentage: Int
    ): Boolean {
        return try {
            val mapsLink = "https://www.google.com/maps?q=$latitude,$longitude"
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            
            val message = "🚨 SAFE KIDS SOS!\n" +
                          "Child: $childName\n" +
                          "Location: $mapsLink\n" +
                          "Battery: $batteryPercentage%\n" +
                          "Time: $timestamp\n" +
                          "Help needed immediately!"

            val smsManager: SmsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Split message if it's too long (160 chars limit)
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(parentPhoneNumber, null, parts, null, null)
            
            Log.d(TAG, "SOS SMS sent to $parentPhoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SOS SMS", e)
            false
        }
    }

    /**
     * Checks if the device has a cellular connection capable of sending SMS.
     */
    fun isSmsCapable(context: Context): Boolean {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        return telephonyManager.phoneType != android.telephony.TelephonyManager.PHONE_TYPE_NONE
    }
}
