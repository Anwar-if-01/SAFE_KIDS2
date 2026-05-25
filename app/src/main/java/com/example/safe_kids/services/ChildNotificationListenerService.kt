package com.example.safe_kids.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.safe_kids.auth.AuthConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log

/**
 * Runs on the CHILD device. Intercepts notifications and forwards them to Firestore
 * so the parent can see them in the Notification Monitoring screen.
 */
class ChildNotificationListenerService : NotificationListenerService() {

    private val TAG = "ChildNotifListener"
    private val db = FirebaseFirestore.getInstance()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        val childId = currentUser.uid

        // Check if sync is enabled and this app is toggled on
        db.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId)
            .get()
            .addOnSuccessListener { snapshot ->
                val notifSettings = snapshot.get("notificationMonitoring") as? Map<String, Any>
                val syncEnabled = notifSettings?.get("syncEnabled") as? Boolean ?: true
                if (!syncEnabled) return@addOnSuccessListener

                @Suppress("UNCHECKED_CAST")
                val appToggles = notifSettings?.get("appToggles") as? Map<String, Boolean> ?: emptyMap()
                val packageName = sbn.packageName ?: return@addOnSuccessListener
                // Default to enabled if not explicitly set
                val appEnabled = appToggles[packageName] ?: true
                if (!appEnabled) return@addOnSuccessListener

                val extras = sbn.notification?.extras
                val title = extras?.getCharSequence("android.title")?.toString() ?: ""
                val text = extras?.getCharSequence("android.text")?.toString() ?: ""
                
                // Get app icon
                val appIcon = try {
                    packageManager.getApplicationIcon(packageName)
                } catch (e: Exception) {
                    null
                }
                val iconBase64 = appIcon?.let { com.example.safe_kids.utils.IconUtils.drawableToBase64(it) }

                if (title.isBlank() && text.isBlank()) return@addOnSuccessListener

                val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val notifData = mapOf(
                    "childId" to childId,
                    "packageName" to packageName,
                    "appName" to (packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()),
                    "title" to title,
                    "text" to text,
                    "timestamp" to System.currentTimeMillis(),
                    "postTime" to sbn.postTime,
                    "date" to dateFormatter.format(java.util.Date()),
                    "iconBase64" to iconBase64
                )

                db.collection("child_notifications")
                    .add(notifData)
                    .addOnSuccessListener { Log.d(TAG, "Forwarded notification from $packageName") }
                    .addOnFailureListener { Log.e(TAG, "Failed to forward notification", it) }
            }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for monitoring
    }
}
