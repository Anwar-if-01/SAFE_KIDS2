package com.example.safe_kids.utils

import android.content.Context
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

enum class PresenceStatus {
    ONLINE,
    IDLE,
    OFFLINE
}

data class PresenceData(
    val isOnline: Boolean = false,
    val lastSeen: Long = 0,
    val deviceInfo: DeviceInfo = DeviceInfo()
)

data class DeviceInfo(
    val batteryLevel: Int = 0,
    val wifiConnected: Boolean = false
)

class PresenceManager(private val context: Context) {
    
    private val firestore = FirebaseFirestore.getInstance()
    private val realtimeDatabase = com.example.safe_kids.utils.FirebaseUtils.getRealtimeDatabase()
    private val auth = FirebaseAuth.getInstance()
    
    companion object {
        private const val ONLINE_THRESHOLD_MS = 3 * 60 * 1000L // 3 minutes
        private const val IDLE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
    }
    
    /**
     * Calculate presence status based on lastSeen timestamp
     */
    fun calculateStatus(lastSeen: Long): PresenceStatus {
        val now = System.currentTimeMillis()
        val timeDiff = now - lastSeen
        
        return when {
            timeDiff < ONLINE_THRESHOLD_MS -> PresenceStatus.ONLINE
            timeDiff < IDLE_THRESHOLD_MS -> PresenceStatus.IDLE
            else -> PresenceStatus.OFFLINE
        }
    }
    
    /**
     * Get formatted time ago string for last seen
     */
    fun getTimeAgo(lastSeen: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - lastSeen
        
        return when {
            diff < 60_000 -> "Active now"
            diff < 3_600_000 -> "${diff / 60_000} minutes ago"
            diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
            else -> "${diff / 86_400_000} days ago"
        }
    }
    
    /**
     * Get current device info (battery and WiFi status)
     */
    fun getCurrentDeviceInfo(): DeviceInfo {
        val batteryLevel = getBatteryLevel()
        val wifiConnected = isWifiConnected()
        return DeviceInfo(batteryLevel, wifiConnected)
    }
    
    private fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
    
    private fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    
    /**
     * Write presence to Firestore for a child
     */
    suspend fun writeFirestorePresence(childId: String, isOnline: Boolean) {
        val deviceInfo = getCurrentDeviceInfo()
        val presenceData = hashMapOf(
            "isOnline" to isOnline,
            "lastSeen" to System.currentTimeMillis(),
            "deviceInfo" to mapOf(
                "batteryLevel" to deviceInfo.batteryLevel,
                "wifiConnected" to deviceInfo.wifiConnected
            )
        )
        
        firestore.collection("users")
            .document(childId)
            .collection("presence")
            .document("current")
            .set(presenceData)
            .await()
    }
    
    /**
     * Update lastSeen timestamp in Firestore
     */
    suspend fun updateLastSeen(childId: String) {
        firestore.collection("users")
            .document(childId)
            .collection("presence")
            .document("current")
            .update("lastSeen", System.currentTimeMillis())
            .await()
    }
    
    /**
     * Set online status in Realtime Database with onDisconnect handler
     */
    fun setRealtimePresence(childId: String, isOnline: Boolean) {
        val presenceRef = realtimeDatabase.getReference("child_presence/$childId/isOnline")
        presenceRef.setValue(isOnline)
        
        // Set onDisconnect to automatically go offline when connection drops
        if (isOnline) {
            presenceRef.onDisconnect().setValue(false)
        }
    }
    
    /**
     * Listen to a child's presence from Realtime Database
     */
    fun listenToChildPresence(childId: String, callback: (PresenceData) -> Unit): ValueEventListener {
        val presenceRef = realtimeDatabase.getReference("child_presence/$childId")
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: System.currentTimeMillis()
                
                val batteryLevel = snapshot.child("deviceInfo/batteryLevel").getValue(Int::class.java) ?: 0
                val wifiConnected = snapshot.child("deviceInfo/wifiConnected").getValue(Boolean::class.java) ?: false
                
                val deviceInfo = DeviceInfo(batteryLevel, wifiConnected)
                val presenceData = PresenceData(isOnline, lastSeen, deviceInfo)
                
                callback(presenceData)
            }
            
            override fun onCancelled(error: DatabaseError) {
                // Handle error
            }
        }
        
        presenceRef.addValueEventListener(listener)
        return listener
    }
    
    /**
     * Listen to a child's presence from Firestore
     */
    fun listenToFirestorePresence(childId: String, callback: (PresenceData) -> Unit): com.google.firebase.firestore.ListenerRegistration {
        return firestore.collection("users")
            .document(childId)
            .collection("presence")
            .document("current")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    callback(PresenceData(isOnline = false, lastSeen = 0))
                    return@addSnapshotListener
                }
                
                val isOnline = snapshot.getBoolean("isOnline") ?: false
                val lastSeen = snapshot.getLong("lastSeen") ?: 0
                
                val deviceInfoMap = snapshot.get("deviceInfo") as? Map<*, *>
                val batteryLevel = deviceInfoMap?.get("batteryLevel") as? Int ?: 0
                val wifiConnected = deviceInfoMap?.get("wifiConnected") as? Boolean ?: false
                
                val deviceInfo = DeviceInfo(batteryLevel, wifiConnected)
                val presenceData = PresenceData(isOnline, lastSeen, deviceInfo)
                
                callback(presenceData)
            }
    }
    
    /**
     * Set offline status when service stops or app is killed
     */
    suspend fun setOffline(childId: String) {
        // Update Firestore
        writeFirestorePresence(childId, false)
        
        // Update Realtime Database
        setRealtimePresence(childId, false)
    }
}
