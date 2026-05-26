package com.example.safe_kids.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.example.safe_kids.services.DeviceAdminService
import com.example.safe_kids.services.SafeKidsAccessibilityService

object PermissionUtils {

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedId = ComponentName(context, SafeKidsAccessibilityService::class.java).flattenToString()
        val settingValue = Settings.Secure.getString(
            context.contentResolver, 
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (settingValue != null) {
            val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(settingValue)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                if (componentNameString.equals(expectedId, ignoreCase = true)) {
                    return true
                }
            }
        }
        
        // Fallback to legacy check if settings read fails
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            val serviceInfo = service.resolveInfo.serviceInfo
            if (serviceInfo.packageName == context.packageName && 
                serviceInfo.name == SafeKidsAccessibilityService::class.java.name) {
                return true
            }
        }
        return false
    }

    fun isUsageStatsPermissionGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    var isNotificationListenerNotSupported = false

    fun isNotificationListenerEnabled(context: Context): Boolean {
        if (isNotificationListenerNotSupported) return true
        return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    fun hasNotificationPolicyAccess(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager?.isNotificationPolicyAccessGranted == true
        } else {
            true
        }
    }

    fun requestNotificationPolicyAccess(context: Context) {
        try {
            // Only request DND access on child devices
            if (!RoleUtils.isChildDevice(context)) {
                android.util.Log.d("PermissionUtils", "Skipping DND settings open on non-child device")
                return
            }
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("PermissionUtils", "Failed to open DND settings", e)
        }
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        return DeviceAdminService.isDeviceAdminActive(context)
    }

    fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val packageName = context.packageName
        try {
            // Primary intent: Direct request dialog (requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission)
            val intent = android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("PermissionUtils", "Primary battery intent failed, trying fallback", e)
            try {
                // Fallback intent: Open the general optimization settings list
                val fallbackIntent = android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                android.util.Log.e("PermissionUtils", "Both battery intents failed", e2)
            }
        }
    }

    fun hasPhonePermissions(context: Context): Boolean {
        return hasPermissions(context, arrayOf(android.Manifest.permission.READ_PHONE_STATE))
    }

    fun hasCallLogPermission(context: Context): Boolean {
        return hasPermissions(context, arrayOf(android.Manifest.permission.READ_CALL_LOG))
    }

    fun hasSendSmsPermission(context: Context): Boolean {
        return hasPermissions(context, arrayOf(android.Manifest.permission.SEND_SMS))
    }

    fun hasCameraPermission(context: Context): Boolean {
        return hasPermissions(context, arrayOf(android.Manifest.permission.CAMERA))
    }

    fun hasSmsPermissions(context: Context): Boolean {
        return hasPermissions(context, arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.RECEIVE_SMS))
    }

    fun hasContactsPermissions(context: Context): Boolean {
        return hasPermissions(context, arrayOf(android.Manifest.permission.READ_CONTACTS))
    }

    fun hasLocationPermissions(context: Context): Boolean {
        return hasPermissions(context, arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermissions(context, arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            true
        }
    }

    fun hasStoragePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermissions(context, arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO))
        } else {
            hasPermissions(context, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    fun hasAllRuntimePermissions(context: Context): Boolean {
        val base = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.CAMERA
        )
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val bgLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            emptyArray()
        }
        return hasPermissions(context, base + media + bgLocation)
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return isIgnoringBatteryOptimizations(context)
    }

    fun hasSmsPermission(context: Context): Boolean {
        return hasSmsPermissions(context)
    }
}
