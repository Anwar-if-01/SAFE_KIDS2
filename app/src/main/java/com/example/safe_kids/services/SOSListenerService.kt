package com.example.safe_kids.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.safe_kids.R
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.ui.parent.EmergencyAlertActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*

class SOSListenerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sosListener: ListenerRegistration? = null
    private var geofenceListener: ListenerRegistration? = null
    private var tamperingListener: ListenerRegistration? = null
    private lateinit var authManager: AuthManagerImpl
    private val TAG = "SOSListenerService"
    private val CHANNEL_ID = "sos_alerts_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        authManager = AuthManagerImpl(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Monitoring for emergency alerts..."))
        
        serviceScope.launch {
            authManager.initialize()
            authManager.currentUser.collect { user ->
                if (user != null && user.childIds.isNotEmpty()) {
                    startListeningForSOS(user.childIds)
                    startListeningForGeofenceAlerts(user.id)
                    startListeningForTamperingAlerts(user.childIds)
                    startListeningForActivityAlerts(user.childIds)
                } else {
                    stopListening()
                }
            }
        }
    }

    private fun startListeningForSOS(childIds: List<String>) {
        stopListening() // Remove old listener if any
        
        Log.d(TAG, "Starting SOS listener for children: $childIds")
        
        sosListener = FirebaseFirestore.getInstance()
            .collection("sos_alerts")
            .whereIn("childId", childIds)
            .whereEqualTo("isResolved", false)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(TAG, "SOS Listen failed.", e)
                    return@addSnapshotListener
                }

                for (doc in snapshots!!.documentChanges) {
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = doc.document
                        triggerEmergencyAlert(
                            alertId = data.id,
                            childName = data.getString("childName") ?: "Child",
                            latitude = data.getDouble("latitude") ?: 0.0,
                            longitude = data.getDouble("longitude") ?: 0.0
                        )
                    }
                }
            }
    }

    private fun triggerEmergencyAlert(alertId: String, childName: String, latitude: Double, longitude: Double) {
        Log.d(TAG, "🚨 SOS ALERT RECEIVED for $childName")
        
        // Trigger Email Notification
        serviceScope.launch {
            authManager.currentUser.value?.email?.let { parentEmail ->
                com.example.safe_kids.utils.EmailNotifier.sendEmailAlert(
                    parentEmail = parentEmail,
                    childName = childName,
                    alertType = "SOS EMERGENCY",
                    message = "$childName has triggered an SOS alert! Immediate attention required.",
                    locationLink = "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
                )
            }
        }

        val intent = Intent(this, EmergencyAlertActivity::class.java).apply {
            putExtra("alert_id", alertId)
            putExtra("child_name", childName)
            putExtra("latitude", latitude)
            putExtra("longitude", longitude)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun startListeningForGeofenceAlerts(parentId: String) {
        geofenceListener?.remove()
        
        Log.d(TAG, "Starting Geofence alert listener for parent: $parentId")
        
        geofenceListener = FirebaseFirestore.getInstance()
            .collection("alerts")
            .document(parentId)
            .collection("geofence_alerts")
            .whereEqualTo("isRead", false)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(TAG, "Geofence Listen failed.", e)
                    return@addSnapshotListener
                }

                for (doc in snapshots!!.documentChanges) {
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = doc.document
                        val childName = data.getString("childName") ?: "Child"
                        val zoneName = data.getString("zoneName") ?: "Zone"
                        val event = data.getString("event") ?: "ENTERED"
                        val zoneType = data.getString("zoneType") ?: "SAFE_ZONE"
                        
                        showGeofenceNotification(childName, zoneName, event, zoneType)
                        
                        // Mark as read
                        data.reference.update("isRead", true)
                    }
                }
            }
    }

    private fun showGeofenceNotification(childName: String, zoneName: String, event: String, zoneType: String) {
        val title = if (zoneType == "SAFE_ZONE") "Safe Zone Alert" else "🚫 Restricted Area Alert"
        val message = if (zoneType == "SAFE_ZONE") {
            if (event == "EXITED") "$childName has LEFT $zoneName!" else "$childName has arrived at $zoneName"
        } else {
            if (event == "ENTERED") "⚠️ $childName has ENTERED restricted area: $zoneName!" else "$childName left $zoneName"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)

        // Trigger Email for Restricted Area entry
        if (zoneType == "RESTRICTED_AREA" && event == "ENTERED") {
            serviceScope.launch {
                authManager.currentUser.value?.email?.let { parentEmail ->
                    com.example.safe_kids.utils.EmailNotifier.sendEmailAlert(
                        parentEmail = parentEmail,
                        childName = childName,
                        alertType = "RESTRICTED AREA BREACH",
                        message = "⚠️ $childName has entered a restricted area: $zoneName."
                    )
                }
            }
        }
    }

    private fun startListeningForTamperingAlerts(childIds: List<String>) {
        tamperingListener?.remove()
        
        Log.d(TAG, "Starting Tampering listener for children: $childIds")
        
        tamperingListener = FirebaseFirestore.getInstance()
            .collection(com.example.safe_kids.auth.AuthConfig.TAMPERING_ALERTS_COLLECTION)
            .whereIn("childId", childIds)
            .whereEqualTo("isResolved", false)
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener

                for (doc in snapshots.documentChanges) {
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = doc.document
                        val trigger = data.getString("trigger") ?: "tampering"
                        val isHighPriority = data.getBoolean("isHighPriority") ?: false
                        
                        showTamperingNotification(trigger, isHighPriority)
                    }
                }
            }
    }

    private fun showTamperingNotification(trigger: String, isHighPriority: Boolean) {
        val title = if (isHighPriority) "🚨 CRITICAL: App Tampering" else "Security Alert"
        val message = "Potential attempt to $trigger the Safe Kids app detected."
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setPriority(if (isHighPriority) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)

        // Trigger Email for high priority tampering
        if (isHighPriority) {
            serviceScope.launch {
                authManager.currentUser.value?.email?.let { parentEmail ->
                    com.example.safe_kids.utils.EmailNotifier.sendEmailAlert(
                        parentEmail = parentEmail,
                        childName = "Your Child",
                        alertType = "APP TAMPERING",
                        message = "🚨 CRITICAL: Someone attempted to $trigger the Safe Kids app on the child device."
                    )
                }
            }
        }
    }

    private fun stopListening() {
        sosListener?.remove()
        sosListener = null
        geofenceListener?.remove()
        geofenceListener = null
        tamperingListener?.remove()
        tamperingListener = null
        activityAlertListener?.remove()
        activityAlertListener = null
    }

    private var activityAlertListener: ListenerRegistration? = null

    private fun startListeningForActivityAlerts(childIds: List<String>) {
        activityAlertListener?.remove()
        
        Log.d(TAG, "Starting Activity Alert listener for children: $childIds")
        
        activityAlertListener = FirebaseFirestore.getInstance()
            .collection(com.example.safe_kids.auth.AuthConfig.ACTIVITY_ALERTS_COLLECTION)
            .whereIn("childId", childIds)
            .whereEqualTo("isRead", false)
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener

                for (doc in snapshots.documentChanges) {
                    if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        val data = doc.document
                        val type = data.getString("type") ?: "GENERAL"
                        val message = data.getString("message") ?: "Activity detected"
                        val childName = data.getString("childName") ?: "Child"
                        
                        showActivityNotification(childName, type, message)
                        
                        // Mark as read
                        data.reference.update("isRead", true)
                    }
                }
            }
    }

    private fun showActivityNotification(childName: String, type: String, message: String) {
        val title = when(type) {
            "LIMIT_REACHED" -> "⏳ Screen Time Limit"
            "APP_BLOCKED" -> "🚫 App Blocked"
            else -> "Activity Alert"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("$childName: $message")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Monitors for SOS alerts from child devices"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Safe Kids Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        serviceScope.cancel()
    }
}
