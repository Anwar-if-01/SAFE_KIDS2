package com.example.safe_kids.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                android.util.Log.d("BootReceiver", "Device rebooted, checking protection state...")
                
                val deviceStateManager = com.example.safe_kids.data.DeviceStateManager(context)
                
                // Use a dedicated scope for the boot receiver
                CoroutineScope(Dispatchers.IO).launch {
                    deviceStateManager.deviceState.collect { state ->
                        if (state.isProtectionEnabled) {
                            android.util.Log.d("BootReceiver", "Protection was enabled, restarting services")
                            startProtectionServices(context)
                        } else {
                            android.util.Log.d("BootReceiver", "Protection was not enabled, skipping service start")
                        }
                        // Important: Cancel the collection after first emission to avoid multiple starts
                        this.cancel()
                    }
                }
            }
        }
    }
    
    private fun startProtectionServices(context: Context) {
        try {
            val serviceIntent = Intent(context, ForegroundProtectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            android.util.Log.d("BootReceiver", "ForegroundProtectionService restarted after boot")
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Failed to restart services after boot", e)
        }
    }
}
