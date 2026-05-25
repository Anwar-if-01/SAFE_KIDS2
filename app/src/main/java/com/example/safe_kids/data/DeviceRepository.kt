package com.example.safe_kids.data

import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.auth.User
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class ChildDeviceStatus(
    val deviceName: String = "Child Device",
    val batteryPercentage: Int? = null,
    val connectionType: String? = null,
    val wifiName: String? = null,
    val lastActive: Long = 0L,
    val isOnline: Boolean = false
)

class DeviceRepository {
    private val firestore = FirebaseFirestore.getInstance()

    fun getDeviceStatusFlow(childId: String): Flow<ChildDeviceStatus> = callbackFlow {
        val db = FirebaseFirestore.getInstance()
        // Read from child_presence/{childId} — this is what ForegroundProtectionService writes to
        val rtdb = FirebaseDatabase.getInstance().getReference("child_presence/$childId")
        
        var lastUser: User? = null
        var lastIsOnline: Boolean = false
        var lastSeen: Long = 0L
        
        fun emitStatus() {
            val user = lastUser ?: return
            // Online if RTDB says online OR lastSeen is within 90 seconds
            val isOnline = lastIsOnline || (System.currentTimeMillis() - lastSeen) < 90_000
            
            val status = ChildDeviceStatus(
                deviceName = user.deviceName ?: "Child Device",
                batteryPercentage = user.batteryPercentage,
                connectionType = user.connectionType,
                wifiName = user.wifiName,
                lastActive = if (lastSeen > 0L) lastSeen else user.lastActive,
                isOnline = isOnline
            )
            trySend(status)
        }

        // Firestore listener for metadata (battery, wifi name, device name, etc.)
        val firestoreRegistration = db.collection(AuthConfig.USERS_COLLECTION)
            .document(childId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("DeviceRepository", "Firestore listener error", error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    lastUser = snapshot.toObject(User::class.java)
                    emitStatus()
                }
            }
            
        // RTDB listener for real-time presence — reads child_presence/{childId}/isOnline and lastSeen
        val rtdbListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                lastIsOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                emitStatus()
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                android.util.Log.e("DeviceRepository", "RTDB listener error", error.toException())
            }
        }
        rtdb.addValueEventListener(rtdbListener)
        
        awaitClose {
            firestoreRegistration.remove()
            rtdb.removeEventListener(rtdbListener)
        }
    }
    
    suspend fun updateDeviceStatus(childId: String, updates: Map<String, Any>) {
        try {
            firestore.collection(AuthConfig.USERS_COLLECTION)
                .document(childId)
                .update(updates)
                .await()
        } catch (e: Exception) {
            // Handle error
            android.util.Log.e("DeviceRepository", "Failed to update device status", e)
        }
    }

    fun getLocationFlow(childId: String): Flow<com.example.safe_kids.data.LocationData?> = callbackFlow {
        val registration = firestore.collection("locations")
            .document(childId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val lat = snapshot.get("latitude") as? Double
                    val lng = snapshot.get("longitude") as? Double
                    val timestamp = snapshot.getLong("timestamp") ?: System.currentTimeMillis()
                    val accuracy = (snapshot.get("accuracy") as? Number)?.toFloat() ?: 0f
                    
                    if (lat != null && lng != null) {
                        trySend(LocationData(lat, lng, timestamp, accuracy))
                    } else {
                        trySend(null)
                    }
                } else {
                    trySend(null)
                }
            }
        awaitClose { registration.remove() }
    }

    suspend fun updateLocation(childId: String, location: com.example.safe_kids.data.LocationData) {
        try {
            val locationMap = mapOf(
                "childId" to childId,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "timestamp" to location.timestamp,
                "accuracy" to location.accuracy
            )
            firestore.collection("locations")
                .document(childId)
                .set(locationMap)
                .await()
        } catch (e: Exception) {
            android.util.Log.e("DeviceRepository", "Failed to update location", e)
        }
    }

    suspend fun sendEmergencyAlert(childId: String, childName: String, location: com.example.safe_kids.data.LocationData, battery: Int): Result<String> {
        return try {
            val alertData = mapOf(
                "childId" to childId,
                "childName" to childName,
                "latitude" to location.latitude,
                "longitude" to location.longitude,
                "battery" to battery,
                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "isResolved" to false
            )
            val docRef = firestore.collection("sos_alerts").add(alertData).await()
            Result.success(docRef.id)
        } catch (e: Exception) {
            android.util.Log.e("DeviceRepository", "Failed to send emergency alert to Firestore", e)
            Result.failure(e)
        }
    }
}
