package com.example.safe_kids.utils

import android.content.Context

object RoleUtils {
    /**
     * Determine whether this device is configured as a child device.
     * Primary source: SharedPreferences key "role" or "user_role" set by onboarding.
     * Returns true only when explicitly set to "child". Otherwise returns false to avoid
     * showing child-device permission dialogs on parent devices.
     */
    fun isChildDevice(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("safe_kids_prefs", Context.MODE_PRIVATE)
            val role = prefs.getString("role", null) ?: prefs.getString("user_role", null)
            role == "child"
        } catch (e: Exception) {
            false
        }
    }
}
