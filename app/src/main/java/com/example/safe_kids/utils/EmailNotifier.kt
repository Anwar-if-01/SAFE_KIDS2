package com.example.safe_kids.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * EmailNotifier provides a way to send email alerts without requiring a custom backend 
 * or Firebase Cloud Functions (avoiding billing).
 * 
 * It uses the EmailJS REST API (free tier) which can be called directly from the Android app.
 * To use this in production:
 * 1. Create a free account at https://www.emailjs.com/
 * 2. Connect an email service (Gmail, Outlook, etc.)
 * 3. Create an email template with placeholders like {{child_name}}, {{alert_type}}, {{message}}
 * 4. Replace the constants below with your actual credentials.
 */
object EmailNotifier {
    private const val TAG = "EmailNotifier"
    
    // --- CONFIGURATION (Injected via build.gradle.kts) ---
    private val EMAILJS_SERVICE_ID = com.example.safe_kids.BuildConfig.EMAILJS_SERVICE_ID 
    private val EMAILJS_TEMPLATE_ID = com.example.safe_kids.BuildConfig.EMAILJS_TEMPLATE_ID
    private val EMAILJS_USER_ID = com.example.safe_kids.BuildConfig.EMAILJS_USER_ID
    // -------------------------------------------------

    /**
     * Sends an email alert to the parent.
     * This is called from the Parent device's SOSListenerService when a critical alert is received.
     */
    suspend fun sendEmailAlert(
        parentEmail: String,
        childName: String,
        alertType: String,
        message: String,
        locationLink: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (EMAILJS_USER_ID == "user_placeholder") {
            Log.w(TAG, "EmailNotifier not configured. Skipping email send.")
            return@withContext false
        }

        try {
            val url = URL("https://api.emailjs.com/api/v1.0/email/send")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val templateParams = JSONObject().apply {
                put("to_email", parentEmail)
                put("child_name", childName)
                put("alert_type", alertType)
                put("message", message)
                put("location_link", locationLink ?: "No location provided")
            }

            val payload = JSONObject().apply {
                put("service_id", EMAILJS_SERVICE_ID)
                put("template_id", EMAILJS_TEMPLATE_ID)
                put("user_id", EMAILJS_USER_ID)
                put("template_params", templateParams)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray())
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                Log.d(TAG, "Email alert sent successfully via EmailJS")
                true
            } else {
                val error = conn.errorStream.bufferedReader().readText()
                Log.e(TAG, "Failed to send email. Code: $responseCode, Error: $error")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending email alert", e)
            false
        }
    }
}
