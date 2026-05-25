package com.example.safe_kids.services.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class SafeKidsNotification(
    val id: String,
    val title: String,
    val message: String,
    val type: NotificationType,
    val timestamp: Long,
    val isRead: Boolean = false,
    val priority: NotificationPriority = NotificationPriority.NORMAL,
    val actionData: Map<String, String> = emptyMap(),
    val targetUserId: String? = null
)

enum class NotificationType {
    LOCATION_ALERT,
    APP_BLOCKED,
    TIME_LIMIT_REACHED,
    EMERGENCY,
    GENERAL,
    FOCUS_MODE_STARTED,
    FOCUS_MODE_ENDED,
    STUDY_MODE_ACTIVATED,
    SLEEP_MODE_ACTIVATED,
    INAPPROPRIATE_CONTENT_DETECTED,
    PAIRING_REQUEST,
    DEVICE_ADMIN_DISABLED
}

enum class NotificationPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

data class NotificationChannel(
    val id: String,
    val name: String,
    val description: String,
    val importance: Int
)

interface NotificationService {
    // Basic notifications
    fun sendNotification(notification: SafeKidsNotification)
    fun sendPushNotification(userId: String, notification: SafeKidsNotification)
    fun getNotifications(): Flow<List<SafeKidsNotification>>
    fun markAsRead(notificationId: String)
    fun clearAllNotifications()
    
    // Permissions and setup
    fun hasNotificationPermission(): Boolean
    fun requestNotificationPermission()
    fun createNotificationChannels()
    
    // Specialized notifications
    fun sendEmergencyAlert(childId: String, message: String, location: String?)
    fun sendAppBlockedNotification(appName: String, reason: String)
    fun sendTimeLimitNotification(appName: String, remainingTime: Long)
    fun sendLocationAlert(childName: String, alertType: String, location: String)
    fun sendFocusModeNotification(isStarted: Boolean, duration: Long?)
    fun sendPairingNotification(parentName: String, code: String)
    
    // Firebase Cloud Messaging
    fun subscribeToTopic(topic: String)
    fun unsubscribeFromTopic(topic: String)
    fun handleFCMMessage(data: Map<String, String>)
    
    // Notification management
    fun enableDoNotDisturb(startTime: Long, endTime: Long)
    fun disableDoNotDisturb()
    fun setQuietHours(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int)
}

class NotificationServiceImpl(private val context: Context) : NotificationService {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val _notifications = MutableStateFlow<List<SafeKidsNotification>>(emptyList())
    
    companion object {
        private const val CHANNEL_EMERGENCY = "emergency"
        private const val CHANNEL_ALERTS = "alerts"
        private const val CHANNEL_GENERAL = "general"
        private const val CHANNEL_FOCUS = "focus"
    }

    init {
        createNotificationChannels()
    }

    override fun sendNotification(notification: SafeKidsNotification) {
        // Add to local notifications list
        val currentList = _notifications.value.toMutableList()
        currentList.add(0, notification) // Add to beginning
        _notifications.value = currentList
        
        // Show system notification
        showSystemNotification(notification)
    }

    override fun sendPushNotification(userId: String, notification: SafeKidsNotification) {
        // TODO: Send via Firebase Cloud Messaging
        sendNotification(notification)
    }

    override fun getNotifications(): Flow<List<SafeKidsNotification>> = _notifications

    override fun markAsRead(notificationId: String) {
        val currentList = _notifications.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == notificationId }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(isRead = true)
            _notifications.value = currentList
        }
    }

    override fun clearAllNotifications() {
        _notifications.value = emptyList()
        notificationManager.cancelAll()
    }

    override fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationManager.areNotificationsEnabled()
        } else {
            true
        }
    }

    override fun requestNotificationPermission() {
        // TODO: Request notification permission through activity
    }

    override fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = listOf(
                NotificationChannel(
                    CHANNEL_EMERGENCY,
                    "Emergency Alerts",
                    "Critical safety alerts and emergency notifications",
                    NotificationManager.IMPORTANCE_HIGH
                ),
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Safety Alerts",
                    "Location alerts, app blocking notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
                NotificationChannel(
                    CHANNEL_GENERAL,
                    "General",
                    "General app notifications and updates",
                    NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    CHANNEL_FOCUS,
                    "Focus & Study",
                    "Focus mode, study mode, and wellbeing notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            
            channels.forEach { channel ->
                val androidChannel = android.app.NotificationChannel(
                    channel.id,
                    channel.name,
                    channel.importance
                ).apply {
                    description = channel.description
                }
                
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(androidChannel)
            }
        }
    }

    override fun sendEmergencyAlert(childId: String, message: String, location: String?) {
        val notification = SafeKidsNotification(
            id = "emergency_${System.currentTimeMillis()}",
            title = "🚨 Emergency Alert",
            message = message + if (location != null) "\nLocation: $location" else "",
            type = NotificationType.EMERGENCY,
            timestamp = System.currentTimeMillis(),
            priority = NotificationPriority.URGENT,
            actionData = mapOf("childId" to childId, "location" to (location ?: ""))
        )
        sendNotification(notification)
    }

    override fun sendAppBlockedNotification(appName: String, reason: String) {
        val notification = SafeKidsNotification(
            id = "app_blocked_${System.currentTimeMillis()}",
            title = "App Blocked",
            message = "$appName has been blocked. Reason: $reason",
            type = NotificationType.APP_BLOCKED,
            timestamp = System.currentTimeMillis(),
            actionData = mapOf("appName" to appName, "reason" to reason)
        )
        sendNotification(notification)
    }

    override fun sendTimeLimitNotification(appName: String, remainingTime: Long) {
        val minutes = remainingTime / (1000 * 60)
        val notification = SafeKidsNotification(
            id = "time_limit_${System.currentTimeMillis()}",
            title = "Time Limit Warning",
            message = "$appName: ${minutes} minutes remaining",
            type = NotificationType.TIME_LIMIT_REACHED,
            timestamp = System.currentTimeMillis(),
            actionData = mapOf("appName" to appName, "remainingTime" to remainingTime.toString())
        )
        sendNotification(notification)
    }

    override fun sendLocationAlert(childName: String, alertType: String, location: String) {
        val notification = SafeKidsNotification(
            id = "location_${System.currentTimeMillis()}",
            title = "Location Alert",
            message = "$childName $alertType at $location",
            type = NotificationType.LOCATION_ALERT,
            timestamp = System.currentTimeMillis(),
            priority = NotificationPriority.HIGH,
            actionData = mapOf("childName" to childName, "alertType" to alertType, "location" to location)
        )
        sendNotification(notification)
    }

    override fun sendFocusModeNotification(isStarted: Boolean, duration: Long?) {
        val message = if (isStarted) {
            val minutes = duration?.let { it / (1000 * 60) } ?: 0
            "Focus mode started for $minutes minutes"
        } else {
            "Focus mode ended"
        }
        
        val notification = SafeKidsNotification(
            id = "focus_${System.currentTimeMillis()}",
            title = "Focus Mode",
            message = message,
            type = if (isStarted) NotificationType.FOCUS_MODE_STARTED else NotificationType.FOCUS_MODE_ENDED,
            timestamp = System.currentTimeMillis()
        )
        sendNotification(notification)
    }

    override fun sendPairingNotification(parentName: String, code: String) {
        val notification = SafeKidsNotification(
            id = "pairing_${System.currentTimeMillis()}",
            title = "Pairing Request",
            message = "$parentName wants to connect. Use code: $code",
            type = NotificationType.PAIRING_REQUEST,
            timestamp = System.currentTimeMillis(),
            priority = NotificationPriority.HIGH,
            actionData = mapOf("parentName" to parentName, "code" to code)
        )
        sendNotification(notification)
    }

    override fun subscribeToTopic(topic: String) {
        // TODO: Implement FCM topic subscription
    }

    override fun unsubscribeFromTopic(topic: String) {
        // TODO: Implement FCM topic unsubscription
    }

    override fun handleFCMMessage(data: Map<String, String>) {
        // TODO: Handle incoming FCM messages
        val notification = SafeKidsNotification(
            id = data["id"] ?: "fcm_${System.currentTimeMillis()}",
            title = data["title"] ?: "Safe Kids",
            message = data["message"] ?: "",
            type = NotificationType.valueOf(data["type"] ?: "GENERAL"),
            timestamp = System.currentTimeMillis(),
            actionData = data
        )
        sendNotification(notification)
    }

    override fun enableDoNotDisturb(startTime: Long, endTime: Long) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Only enable DND on child devices
                if (!com.example.safe_kids.utils.RoleUtils.isChildDevice(context)) {
                    android.util.Log.d("NotificationService", "Skipping enable DND on non-child device")
                    return
                }
                if (manager.isNotificationPolicyAccessGranted) {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    android.util.Log.d("NotificationService", "Do Not Disturb enabled")
                } else {
                    android.util.Log.w("NotificationService", "Missing Notification Policy Access")
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationService", "Error enabling DND", e)
            }
        }
    }

    override fun disableDoNotDisturb() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Only disable DND on child devices (skip on parent)
                if (!com.example.safe_kids.utils.RoleUtils.isChildDevice(context)) {
                    android.util.Log.d("NotificationService", "Skipping disable DND on non-child device")
                    return
                }
                if (manager.isNotificationPolicyAccessGranted) {
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    android.util.Log.d("NotificationService", "Do Not Disturb disabled")
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationService", "Error disabling DND", e)
            }
        }
    }

    override fun setQuietHours(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        // TODO: Set quiet hours for notifications
    }

    private fun showSystemNotification(notification: SafeKidsNotification) {
        val channelId = when (notification.type) {
            NotificationType.EMERGENCY -> CHANNEL_EMERGENCY
            NotificationType.LOCATION_ALERT, NotificationType.APP_BLOCKED -> CHANNEL_ALERTS
            NotificationType.FOCUS_MODE_STARTED, NotificationType.FOCUS_MODE_ENDED,
            NotificationType.STUDY_MODE_ACTIVATED, NotificationType.SLEEP_MODE_ACTIVATED -> CHANNEL_FOCUS
            else -> CHANNEL_GENERAL
        }

        val priority = when (notification.priority) {
            NotificationPriority.LOW -> NotificationCompat.PRIORITY_LOW
            NotificationPriority.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
            NotificationPriority.HIGH -> NotificationCompat.PRIORITY_HIGH
            NotificationPriority.URGENT -> NotificationCompat.PRIORITY_MAX
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setPriority(priority)
            .setAutoCancel(true)
            .setWhen(notification.timestamp)

        // Add action buttons for certain notification types
        when (notification.type) {
            NotificationType.EMERGENCY -> {
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }
            NotificationType.PAIRING_REQUEST -> {
                // TODO: Add accept/decline action buttons
            }
            else -> {}
        }

        try {
            notificationManager.notify(notification.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // Handle notification permission error
        }
    }
}
