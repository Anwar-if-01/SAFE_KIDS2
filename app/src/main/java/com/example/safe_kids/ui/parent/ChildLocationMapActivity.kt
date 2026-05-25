package com.example.safe_kids.ui.parent

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.graphics.Color
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.R
import com.example.safe_kids.auth.ParentActionsManager
import com.example.safe_kids.data.LocationData
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*
import com.google.android.material.card.MaterialCardView

class ChildLocationMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private lateinit var firestore: FirebaseFirestore
    private lateinit var parentActionsManager: ParentActionsManager
    private var childMarker: Marker? = null
    private var parentMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var locationListener: ListenerRegistration? = null
    private var presenceListener: com.google.firebase.database.ValueEventListener? = null
    private var childId: String = ""

    // Views
    private lateinit var tvAddress: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var tvChildName: TextView
    private lateinit var loadingOverlay: View
    private lateinit var presenceIndicator: View
    private lateinit var tvPresenceText: TextView
    private var statusOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_location)

        // Get childId passed from previous screen
        childId = intent.getStringExtra("childId") ?: ""
        val childName = intent.getStringExtra("childName") ?: "Child"

        firestore = FirebaseFirestore.getInstance()
        parentActionsManager = ParentActionsManager(firestore)

        // Bind views
        tvAddress = findViewById(R.id.tvAddress)
        tvDistance = findViewById(R.id.tvDistance)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        tvChildName = findViewById(R.id.tvChildName)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        presenceIndicator = findViewById(R.id.presenceIndicator)
        tvPresenceText = findViewById(R.id.tvPresenceText)

        tvChildName.text = "$childName's Location"
        
        // Start presence tracking
        startPresenceTracking()

        // Init map
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Refresh button
        findViewById<View>(R.id.fabRefresh).setOnClickListener {
            requestChildLocation()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = false

        // Request location immediately when map is ready
        requestChildLocation()
    }

    private fun requestChildLocation() {
        val parentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (childId.isEmpty() || parentUid == null) {
            Toast.makeText(this, "Authentication or Child ID not found", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)

        val commandData = hashMapOf(
            "type" to "REQUEST_LOCATION",
            "childId" to childId,
            "parentId" to parentUid,
            "status" to "pending",
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )

        firestore.collection("child_commands")
            .add(commandData)
            .addOnSuccessListener {
                listenForLocationResponse()
            }
            .addOnFailureListener { e ->
                showLoading(false)
                Toast.makeText(
                    this@ChildLocationMapActivity,
                    "Failed to request location: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun listenForLocationResponse() {
        val locationsQuery = firestore.collection("locations")
            .whereEqualTo("childId", childId)
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)

        // Remove old listener
        locationListener?.remove()

        locationListener = locationsQuery.addSnapshotListener { snapshots, error ->
            if (error != null) {
                showLoading(false)
                Toast.makeText(
                    this@ChildLocationMapActivity,
                    "Error listening for location: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
                return@addSnapshotListener
            }

            if (snapshots != null && snapshots.isEmpty) {
                // No location documents yet — inform the parent while keeping listener active
                showLoading(false)
                showStatusMessage("No location data available yet", false)
                return@addSnapshotListener
            }

            if (snapshots != null && !snapshots.isEmpty) {
                val doc = snapshots.documents[0]
                val lat = doc.getDouble("latitude")
                val lng = doc.getDouble("longitude")
                val timestamp = doc.getLong("timestamp")
                val accuracy = doc.getDouble("accuracy")?.toFloat() ?: 0f

                if (lat != null && lng != null) {
                    // New data arrived — clear any status overlay and update map
                    dismissStatusMessage()
                    showLoading(false)
                    updateMapWithChildLocation(lat, lng, timestamp, accuracy)

                    // Stop listening after receiving
                    locationListener?.remove()
                    locationListener = null
                }
            }
        }

        // Timeout after 15 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            if (loadingOverlay.visibility == View.VISIBLE) {
                showLoading(false)
                // Stop current listener and show persistent retry UI
                locationListener?.remove()
                locationListener = null
                showStatusMessage("Unable to reach child's device. Check your connection and try again.", true)
            }
        }, 15000)
    }

    private fun updateMapWithChildLocation(lat: Double, lng: Double, updatedAt: Long?, accuracy: Float) {
        val childLatLng = LatLng(lat, lng)

        // Remove old marker
        childMarker?.remove()

        // Add child marker
        childMarker = googleMap.addMarker(
            MarkerOptions()
                .position(childLatLng)
                .title("Child's Location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
        )

        // Move camera to child
        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(childLatLng, 15f))

        // Get address
        getAddressFromLatLng(lat, lng)

        // Get parent location and calculate distance
        getParentLocationAndDistance(lat, lng)

        // Update timestamp
        updatedAt?.let {
            val timeAgo = getTimeAgo(it)
            tvLastUpdated.text = "Last updated: $timeAgo"
        }
    }

    private fun getParentLocationAndDistance(childLat: Double, childLng: Double) {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tvDistance.text = "Distance: Enable location permission"
            return
        }

        LocationServices.getFusedLocationProviderClient(this)
            .lastLocation
            .addOnSuccessListener { location ->
                location?.let {
                    val parentLatLng = LatLng(it.latitude, it.longitude)

                    // Add parent marker
                    parentMarker?.remove()
                    parentMarker = googleMap.addMarker(
                        MarkerOptions()
                            .position(parentLatLng)
                            .title("Your Location")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    )

                    // Draw line between parent and child
                    routePolyline?.remove()
                    routePolyline = googleMap.addPolyline(
                        PolylineOptions()
                            .add(parentLatLng, LatLng(childLat, childLng))
                            .color(android.graphics.Color.parseColor("#6C63FF"))
                            .width(4f)
                    )

                    // Calculate distance
                    val distanceMeters = calculateDistance(
                        it.latitude, it.longitude, childLat, childLng
                    )

                    val distanceText = when {
                        distanceMeters < 1000 -> "${distanceMeters.toInt()} meters away"
                        else -> String.format(Locale.US, "%.1f km away", distanceMeters / 1000.0)
                    }

                    tvDistance.text = "📍 $distanceText"

                    // Zoom to show both markers
                    val bounds = LatLngBounds.builder()
                        .include(parentLatLng)
                        .include(LatLng(childLat, childLng))
                        .build()
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150))
                }
            }
    }

    private fun getAddressFromLatLng(lat: Double, lng: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@ChildLocationMapActivity, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                withContext(Dispatchers.Main) {
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val addressText = buildString {
                            if (!address.thoroughfare.isNullOrEmpty()) append(address.thoroughfare)
                            if (!address.locality.isNullOrEmpty()) append(", ${address.locality}")
                            if (!address.countryName.isNullOrEmpty()) append(", ${address.countryName}")
                        }
                        tvAddress.text = addressText.ifEmpty { "Address not available" }
                    } else {
                        tvAddress.text = "Address not available"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvAddress.text = "Address not available"
                }
            }
        }
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${diff / 60000} minutes ago"
            diff < 86400000 -> "${diff / 3600000} hours ago"
            else -> "${diff / 86400000} days ago"
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showStatusMessage(message: String, showRetry: Boolean) {
        runOnUiThread {
            dismissStatusMessage()

            val root = findViewById<View>(android.R.id.content) as ViewGroup

            val overlay = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val card = com.google.android.material.card.MaterialCardView(context).apply {
                    radius = 12f
                    setCardBackgroundColor(android.graphics.Color.WHITE)
                    val inner = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(32, 32, 32, 32)
                        val tv = TextView(context).apply {
                            text = message
                            setTextColor(android.graphics.Color.DKGRAY)
                            textSize = 16f
                        }
                        addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                        if (showRetry) {
                            val retryBtn = Button(context).apply {
                                text = "Retry"
                                setOnClickListener {
                                    // restart the request
                                    dismissStatusMessage()
                                    requestChildLocation()
                                }
                            }
                            val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            btnParams.topMargin = 16
                            addView(retryBtn, btnParams)
                        }
                    }
                    addView(inner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                }
                addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                    leftMargin = 48
                    rightMargin = 48
                })
            }

            root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            statusOverlay = overlay
        }
    }

    private fun dismissStatusMessage() {
        runOnUiThread {
            statusOverlay?.let { v ->
                val root = findViewById<View>(android.R.id.content) as ViewGroup
                try {
                    root.removeView(v)
                } catch (e: Exception) {
                    // ignore
                }
            }
            statusOverlay = null
        }
    }

    private fun startPresenceTracking() {
        val database = FirebaseDatabase.getInstance()
        val presenceRef = database.getReference("child_presence/$childId")
        
        presenceListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0
                
                updatePresenceIndicator(isOnline, lastSeen)
            }
            
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                // Handle error
            }
        }
        
        presenceRef.addValueEventListener(presenceListener!!)
    }
    
    private fun updatePresenceIndicator(isOnline: Boolean, lastSeen: Long) {
        val presenceStatus = when {
            isOnline && (System.currentTimeMillis() - lastSeen) < 3 * 60 * 1000L -> com.example.safe_kids.utils.PresenceStatus.ONLINE
            (System.currentTimeMillis() - lastSeen) < 10 * 60 * 1000L -> com.example.safe_kids.utils.PresenceStatus.IDLE
            else -> com.example.safe_kids.utils.PresenceStatus.OFFLINE
        }
        
        val statusColor = when (presenceStatus) {
            com.example.safe_kids.utils.PresenceStatus.ONLINE -> android.graphics.Color.parseColor("#4CAF50") // Green
            com.example.safe_kids.utils.PresenceStatus.IDLE -> android.graphics.Color.parseColor("#FFA000") // Yellow
            com.example.safe_kids.utils.PresenceStatus.OFFLINE -> android.graphics.Color.parseColor("#9E9E9E") // Gray
        }
        
        val statusText = when (presenceStatus) {
            com.example.safe_kids.utils.PresenceStatus.ONLINE -> "• Active now"
            com.example.safe_kids.utils.PresenceStatus.IDLE -> {
                val minutesAgo = ((System.currentTimeMillis() - lastSeen) / 60_000L).toInt()
                "• Last seen $minutesAgo minutes ago"
            }
            com.example.safe_kids.utils.PresenceStatus.OFFLINE -> {
                if (lastSeen > 0) {
                    val hoursAgo = ((System.currentTimeMillis() - lastSeen) / 3_600_000L).toInt()
                    if (hoursAgo < 24) "• Last seen $hoursAgo hours ago"
                    else "• Offline"
                } else "• Offline"
            }
        }
        
        presenceIndicator.setBackgroundColor(statusColor)
        tvPresenceText.text = statusText
    }

    override fun onDestroy() {
        super.onDestroy()
        locationListener?.remove()
        presenceListener?.let {
            FirebaseDatabase.getInstance().getReference("child_presence/$childId").removeEventListener(it)
        }

        try {
            childMarker?.remove()
        } catch (e: Exception) { }
        try {
            parentMarker?.remove()
        } catch (e: Exception) { }
        try {
            routePolyline?.remove()
        } catch (e: Exception) { }

        if (::googleMap.isInitialized) {
            try { googleMap.clear() } catch (e: Exception) { }
        }

        // Ensure any status overlay view is removed
        try { dismissStatusMessage() } catch (e: Exception) { }
    }
}
