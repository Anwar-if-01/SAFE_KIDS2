package com.example.safe_kids.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class DeviceStatus(
    val deviceName: String,
    val batteryPercentage: Int,
    val connectionType: String,
    val isConnected: Boolean
)

class DeviceStatusMonitor(private val context: Context) {

    fun getStatusFlow(): Flow<DeviceStatus> = flow {
        while (true) {
            emit(getCurrentStatus())
            delay(5000) // Update every 5 seconds
        }
    }

    fun getCurrentStatus(): DeviceStatus {
        val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } else {
            Settings.Global.getString(context.contentResolver, "device_name")
        } ?: Build.MODEL

        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 0

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)
        val isConnected = caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )
        val connectionType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile Data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Disconnected"
        }

        return DeviceStatus(deviceName, batteryPct, connectionType, isConnected)
    }
}
