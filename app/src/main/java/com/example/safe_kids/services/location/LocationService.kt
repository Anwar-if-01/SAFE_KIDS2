package com.example.safe_kids.services.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import com.google.android.gms.location.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.tasks.await

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: Float,
    val address: String? = null
)

enum class ZoneType {
    SAFE_ZONE,        // Alert on LEAVE
    RESTRICTED_AREA   // Alert on ENTER
}

data class GeofenceArea(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float, // in meters
    val type: ZoneType = ZoneType.SAFE_ZONE,
    val isActive: Boolean = true
)

data class LocationAlert(
    val id: String,
    val childId: String,
    val alertType: LocationAlertType,
    val location: LocationData,
    val geofence: GeofenceArea?,
    val timestamp: Long
)

enum class LocationAlertType {
    ENTERED_SAFE_ZONE,
    LEFT_SAFE_ZONE,
    ENTERED_RESTRICTED_ZONE,
    EMERGENCY_LOCATION,
    LOCATION_SHARING_DISABLED
}

interface LocationService {
    // Basic location tracking
    fun startLocationTracking()
    fun stopLocationTracking()
    fun setChildId(id: String)
    fun getCurrentLocation(): Flow<LocationData?>
    fun getLocationHistory(startTime: Long, endTime: Long): Flow<List<LocationData>>
    
    // Permissions
    fun isLocationPermissionGranted(): Boolean
    fun isBackgroundLocationPermissionGranted(): Boolean
    fun requestLocationPermission()
    fun requestBackgroundLocationPermission()
    
    // Geofencing
    fun addGeofence(geofence: GeofenceArea)
    fun removeGeofence(geofenceId: String)
    fun getGeofences(): Flow<List<GeofenceArea>>
    fun isInGeofence(location: LocationData, geofence: GeofenceArea): Boolean
    
    // Location sharing and alerts
    fun enableLocationSharing(childId: String)
    fun disableLocationSharing(childId: String)
    fun sendLocationAlert(alert: LocationAlert)
    fun getLocationAlerts(): Flow<List<LocationAlert>>
    
    // Emergency features
    fun sendEmergencyLocation(childId: String)
    fun enableSafeZoneAlerts(geofences: List<GeofenceArea>)
}

class LocationServiceImpl(
    private val context: Context,
    private val authManager: com.example.safe_kids.auth.AuthManager
) : LocationService {
    private val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
    private val deviceRepository = com.example.safe_kids.data.DeviceRepository()
    private val firestore = FirebaseFirestore.getInstance()
    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    private val _locationHistory = MutableStateFlow<List<LocationData>>(emptyList())
    private val _geofences = MutableStateFlow<List<GeofenceArea>>(emptyList())
    private val _locationAlerts = MutableStateFlow<List<LocationAlert>>(emptyList())
    
    private var isTracking = false
    private var childId: String? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val geofenceStateDao = com.example.safe_kids.data.local.AppDatabase.getDatabase(context).geofenceStateDao()

    private val locationCallback = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            result.lastLocation?.let { location ->
                val locationData = LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    timestamp = System.currentTimeMillis(),
                    accuracy = location.accuracy
                )
                
                _currentLocation.value = locationData
                
                // Update history
                val history = _locationHistory.value.toMutableList()
                history.add(locationData)
                if (history.size > 100) history.removeAt(0)
                _locationHistory.value = history
                
                // Upload to Firebase if childId is set
                childId?.let { id ->
                    scope.launch {
                        deviceRepository.updateLocation(id, com.example.safe_kids.data.LocationData(
                            locationData.latitude,
                            locationData.longitude,
                            locationData.timestamp,
                            locationData.accuracy
                        ))
                    }
                }
                
                checkGeofenceAlerts(locationData)
            }
        }
    }

    override fun setChildId(id: String) {
        if (this.childId == id) return
        this.childId = id
        syncGeofences()
    }

    private var geofenceJob: Job? = null
    
    private fun syncGeofences() {
        val currentChildId = childId ?: return
        geofenceJob?.cancel()
        
        geofenceJob = scope.launch {
            // First, get the parent IDs for this child
            val userDoc = firestore.collection(com.example.safe_kids.auth.AuthConfig.USERS_COLLECTION)
                .document(currentChildId)
                .get()
                .await()
            val user = userDoc.toObject(com.example.safe_kids.auth.User::class.java) ?: return@launch
            val parentIds = user.parentIds
            if (parentIds.isEmpty()) return@launch
            
            // Listen to both SAFE_ZONES and RESTRICTED_AREAS for all parents
            // For simplicity and to avoid complex queries, we fetch from the primary parent (parentIds[0])
            val primaryParentId = parentIds[0]
            
            val safeZonesRef = firestore.collection(com.example.safe_kids.auth.AuthConfig.SAFE_ZONES_COLLECTION)
                .document(primaryParentId)
                .collection("zones")
                
            val restrictedAreasRef = firestore.collection(com.example.safe_kids.auth.AuthConfig.RESTRICTED_AREAS_COLLECTION)
                .document(primaryParentId)
                .collection("zones")

            // Real-time listener for Safe Zones
            safeZonesRef.addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val safeZones = snapshot.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val lat = doc.getDouble("latitude") ?: return@mapNotNull null
                    val lng = doc.getDouble("longitude") ?: return@mapNotNull null
                    val rad = doc.getDouble("radius")?.toFloat() ?: 100f
                    GeofenceArea(doc.id, name, lat, lng, rad, ZoneType.SAFE_ZONE)
                }
                updateMergedGeofences(safeZones, ZoneType.SAFE_ZONE)
            }

            // Real-time listener for Restricted Areas
            restrictedAreasRef.addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                val restrictedAreas = snapshot.documents.mapNotNull { doc ->
                    val name = doc.getString("name") ?: return@mapNotNull null
                    val lat = doc.getDouble("latitude") ?: return@mapNotNull null
                    val lng = doc.getDouble("longitude") ?: return@mapNotNull null
                    val rad = doc.getDouble("radius")?.toFloat() ?: 100f
                    GeofenceArea(doc.id, name, lat, lng, rad, ZoneType.RESTRICTED_AREA)
                }
                updateMergedGeofences(restrictedAreas, ZoneType.RESTRICTED_AREA)
            }
        }
    }

    private fun updateMergedGeofences(newZones: List<GeofenceArea>, type: ZoneType) {
        val current = _geofences.value.toMutableList()
        current.removeAll { it.type == type }
        current.addAll(newZones)
        _geofences.value = current
        android.util.Log.d("LocationService", "Synced ${newZones.size} $type(s). Total: ${current.size}")
    }

    override fun startLocationTracking() {
        if (!isLocationPermissionGranted() || isTracking) return
        
        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
            30000L // 30 seconds
        ).setMinUpdateIntervalMillis(15000L).build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, android.os.Looper.getMainLooper())
            isTracking = true
            android.util.Log.d("LocationService", "Fused location tracking started")
        } catch (e: SecurityException) {
            android.util.Log.e("LocationService", "Location permission missing", e)
        }
    }

    override fun stopLocationTracking() {
        if (!isTracking) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isTracking = false
    }

    override fun getCurrentLocation(): Flow<LocationData?> = _currentLocation

    override fun getLocationHistory(startTime: Long, endTime: Long): Flow<List<LocationData>> {
        return _locationHistory
    }

    override fun isLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isBackgroundLocationPermissionGranted(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            isLocationPermissionGranted()
        }
    }

    override fun requestLocationPermission() {}
    override fun requestBackgroundLocationPermission() {}

    override fun addGeofence(geofence: GeofenceArea) {
        val currentList = _geofences.value.toMutableList()
        currentList.add(geofence)
        _geofences.value = currentList
    }

    override fun removeGeofence(geofenceId: String) {
        val currentList = _geofences.value.toMutableList()
        currentList.removeAll { it.id == geofenceId }
        _geofences.value = currentList
    }

    override fun getGeofences(): Flow<List<GeofenceArea>> = _geofences

    override fun isInGeofence(location: LocationData, geofence: GeofenceArea): Boolean {
        val distance = calculateDistance(
            location.latitude, location.longitude,
            geofence.latitude, geofence.longitude
        )
        return distance <= geofence.radius
    }

    override fun enableLocationSharing(childId: String) {
        setChildId(childId)
        startLocationTracking()
    }

    override fun disableLocationSharing(childId: String) {
        stopLocationTracking()
    }

    override fun sendLocationAlert(alert: LocationAlert) {
        val currentAlerts = _locationAlerts.value.toMutableList()
        currentAlerts.add(alert)
        _locationAlerts.value = currentAlerts
    }

    override fun getLocationAlerts(): Flow<List<LocationAlert>> = _locationAlerts

    override fun sendEmergencyLocation(childId: String) {
        val currentLocation = _currentLocation.value
        if (currentLocation == null) {
            android.util.Log.e("LocationService", "Cannot send SOS: Location unknown")
            return
        }

        scope.launch {
            val user = authManager.getCurrentUser()
            val childName = user?.displayName ?: "Your Child"
            val battery = user?.batteryPercentage ?: 0
            
            val locationForRepo = com.example.safe_kids.data.LocationData(
                currentLocation.latitude,
                currentLocation.longitude,
                currentLocation.timestamp,
                currentLocation.accuracy
            )

            // 1. Primary: Firebase/FCM
            val fcmResult = deviceRepository.sendEmergencyAlert(childId, childName, locationForRepo, battery)
            
            // 2. Fallback: SMS (if FCM fails or offline)
            if (fcmResult.isFailure || !isNetworkAvailable(context)) {
                android.util.Log.w("LocationService", "FCM failed or offline, triggering SMS fallback")
                
                val parentsResult = authManager.getParentsForChild(childId)
                if (parentsResult.isSuccess) {
                    val parents = parentsResult.getOrDefault(emptyList())
                    parents.forEach { parent ->
                        parent.phoneNumber?.let { phone ->
                            com.example.safe_kids.utils.SmsFallbackManager.sendSosSms(
                                context = context,
                                parentPhoneNumber = phone,
                                childName = childName,
                                latitude = currentLocation.latitude,
                                longitude = currentLocation.longitude,
                                batteryPercentage = battery
                            )
                        }
                    }
                }
            }

            // Also update local alert flow
            val alert = LocationAlert(
                id = "emergency_${System.currentTimeMillis()}",
                childId = childId,
                alertType = LocationAlertType.EMERGENCY_LOCATION,
                location = currentLocation,
                geofence = null,
                timestamp = System.currentTimeMillis()
            )
            sendLocationAlert(alert)
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnectedOrConnecting == true
        }
    }

    override fun enableSafeZoneAlerts(geofences: List<GeofenceArea>) {
        _geofences.value = geofences
    }

    private fun checkGeofenceAlerts(location: LocationData) {
        val currentChildId = childId ?: return
        
        scope.launch {
            val user = authManager.getCurrentUser() ?: return@launch
            val parentIds = user.parentIds
            if (parentIds.isEmpty()) return@launch

            _geofences.value.forEach { geofence ->
                if (!geofence.isActive) return@forEach
                
                val isCurrentlyInside = isInGeofence(location, geofence)
                val previousState = geofenceStateDao.getState(geofence.id)
                val wasInside = previousState?.isInside ?: false

                // Detect state change
                val event = when {
                    isCurrentlyInside && !wasInside -> "ENTERED"
                    !isCurrentlyInside && wasInside -> "EXITED"
                    else -> null
                }

                if (event != null) {
                    android.util.Log.d("LocationService", "Geofence event: $event for ${geofence.name} (${geofence.type})")
                    
                    // 1. Update local state
                    geofenceStateDao.updateState(com.example.safe_kids.data.local.GeofenceStateEntity(
                        zoneId = geofence.id,
                        isInside = isCurrentlyInside
                    ))

                    // 2. Logic: Only alert for specific events based on zone type
                    val shouldAlert = when (geofence.type) {
                        ZoneType.SAFE_ZONE -> event == "EXITED"
                        ZoneType.RESTRICTED_AREA -> event == "ENTERED"
                    }

                    if (shouldAlert) {
                        // Write alert to Firestore for each parent
                        parentIds.forEach { parentId ->
                            val alertData = mapOf(
                                "childId" to currentChildId,
                                "childName" to (user.displayName ?: "Your Child"),
                                "zoneName" to geofence.name,
                                "zoneType" to geofence.type.name,
                                "event" to event,
                                "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                                "isRead" to false,
                                "latitude" to location.latitude,
                                "longitude" to location.longitude
                            )

                            firestore.collection("alerts")
                                .document(parentId)
                                .collection("geofence_alerts")
                                .add(alertData)
                                .addOnFailureListener { e ->
                                    android.util.Log.e("LocationService", "Failed to upload geofence alert", e)
                                }
                        }

                        // 3. Update local alert flow
                        val alertType = if (geofence.type == ZoneType.SAFE_ZONE) {
                            LocationAlertType.LEFT_SAFE_ZONE
                        } else {
                            LocationAlertType.ENTERED_RESTRICTED_ZONE
                        }
                        
                        sendLocationAlert(LocationAlert(
                            id = "geofence_${System.currentTimeMillis()}",
                            childId = currentChildId,
                            alertType = alertType,
                            location = location,
                            geofence = geofence,
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                } else if (previousState == null) {
                    // Initialize state if it's the first check
                    geofenceStateDao.updateState(com.example.safe_kids.data.local.GeofenceStateEntity(
                        zoneId = geofence.id,
                        isInside = isCurrentlyInside
                    ))
                }
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
