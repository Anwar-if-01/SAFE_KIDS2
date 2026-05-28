package com.example.safe_kids.services

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class DeviceAdminService : DeviceAdminReceiver() {
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device admin enabled - prevent uninstall
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Device admin disabled
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Launch PIN screen immediately when deactivation is attempted
        val protectionIntent = Intent(context, UninstallProtectionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("trigger", "device_admin_disable")
        }
        context.startActivity(protectionIntent)

        return "Safe Kids parental PIN is required to disable protection."
    }

    companion object {
        fun isDeviceAdminActive(context: Context): Boolean {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceAdminService::class.java)
            return devicePolicyManager.isAdminActive(componentName)
        }

        fun requestDeviceAdminPermission(context: Context): Intent {
            val componentName = ComponentName(context, DeviceAdminService::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable device admin to prevent uninstallation and ensure child safety"
            )
            return intent
        }

        fun disableDeviceAdmin(context: Context) {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceAdminService::class.java)
            devicePolicyManager.removeActiveAdmin(componentName)
        }
    }
}
