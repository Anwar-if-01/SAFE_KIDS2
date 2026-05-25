package com.example.safe_kids.services

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.DeviceRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

class DeviceStatusWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val deviceRepository = DeviceRepository()

    override suspend fun doWork(): Result {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return Result.success()

        return try {
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                context.registerReceiver(null, ifilter)
            }
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else null

            var connectionType = "Offline"
            var wifiName: String? = null
            
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            if (network != null) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        connectionType = "WiFi"
                        try {
                            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            val info = wifiManager.connectionInfo
                            val ssid = info.ssid
                            if (ssid != null && ssid != "<unknown ssid>") {
                                wifiName = ssid.replace("\"", "")
                            }
                        } catch (e: Exception) {
                            // Ignore permission errors if any
                        }
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        connectionType = "Mobile"
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        connectionType = "Ethernet"
                    }
                }
            }

            val updates = mutableMapOf<String, Any>(
                "lastActive" to System.currentTimeMillis(),
                "connectionType" to connectionType
            )
            
            if (batteryPct != null) {
                updates["batteryPercentage"] = batteryPct
            }
            if (wifiName != null) {
                updates["wifiName"] = wifiName
            } else {
                updates["wifiName"] = com.google.firebase.firestore.FieldValue.delete()
            }

            deviceRepository.updateDeviceStatus(currentUser.uid, updates)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("DeviceStatusWorker", "Failed to sync device status", e)
            Result.retry()
        }
    }
}
