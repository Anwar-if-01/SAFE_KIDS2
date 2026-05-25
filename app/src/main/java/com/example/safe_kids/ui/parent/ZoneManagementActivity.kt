package com.example.safe_kids.ui.parent

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.services.location.ZoneType
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class ZoneItem(
    val id: String = "",
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radius: Float = 100f,
    val type: ZoneType = ZoneType.SAFE_ZONE
)

class ZoneManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val childId = intent.getStringExtra("CHILD_ID") ?: ""
        val childName = intent.getStringExtra("CHILD_NAME") ?: "Child"

        setContent {
            SAFE_KIDSTheme {
                ZoneManagementScreen(childId, childName, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneManagementScreen(childId: String, childName: String, onBack: () -> Unit) {
    val firestore = FirebaseFirestore.getInstance()
    val parentId = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val scope = rememberCoroutineScope()
    
    var zones by remember { mutableStateOf<List<ZoneItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Fetch zones from both collections
    LaunchedEffect(parentId) {
        if (parentId.isEmpty()) return@LaunchedEffect
        
        val safeZonesJob = firestore.collection(AuthConfig.SAFE_ZONES_COLLECTION)
            .document(parentId)
            .collection("zones")
            .get()
            
        val restrictedAreasJob = firestore.collection(AuthConfig.RESTRICTED_AREAS_COLLECTION)
            .document(parentId)
            .collection("zones")
            .get()
            
        try {
            val safeZones = safeZonesJob.await().documents.map { doc ->
                doc.toObject(ZoneItem::class.java)?.copy(id = doc.id, type = ZoneType.SAFE_ZONE) ?: ZoneItem()
            }
            val restrictedAreas = restrictedAreasJob.await().documents.map { doc ->
                doc.toObject(ZoneItem::class.java)?.copy(id = doc.id, type = ZoneType.RESTRICTED_AREA) ?: ZoneItem()
            }
            zones = safeZones + restrictedAreas
        } catch (e: Exception) {
            // Handle error
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zone Management") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Zone")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (zones.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No zones defined. Add a Safe Zone or Restricted Area to start monitoring.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    item {
                        Text("Zones for $childName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(16.dp))
                    }
                    
                    items(zones) { zone ->
                        ZoneCard(zone, onDelete = {
                            scope.launch {
                                val collection = if (zone.type == ZoneType.SAFE_ZONE) AuthConfig.SAFE_ZONES_COLLECTION else AuthConfig.RESTRICTED_AREAS_COLLECTION
                                firestore.collection(collection)
                                    .document(parentId)
                                    .collection("zones")
                                    .document(zone.id)
                                    .delete()
                                    .await()
                                zones = zones.filter { it.id != zone.id }
                            }
                        })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddZoneDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, lat, lng, radius, type ->
                scope.launch {
                    val collection = if (type == ZoneType.SAFE_ZONE) AuthConfig.SAFE_ZONES_COLLECTION else AuthConfig.RESTRICTED_AREAS_COLLECTION
                    val data = mapOf(
                        "name" to name,
                        "latitude" to lat,
                        "longitude" to lng,
                        "radius" to radius.toFloat(),
                        "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                    
                    val ref = firestore.collection(collection)
                        .document(parentId)
                        .collection("zones")
                        .add(data)
                        .await()
                        
                    zones = zones + ZoneItem(ref.id, name, lat, lng, radius.toFloat(), type)
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
fun ZoneCard(zone: ZoneItem, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (zone.type == ZoneType.SAFE_ZONE) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (zone.type == ZoneType.SAFE_ZONE) Icons.Default.VerifiedUser else Icons.Default.Warning,
                contentDescription = null,
                tint = if (zone.type == ZoneType.SAFE_ZONE) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(zone.name, fontWeight = FontWeight.Bold)
                Text(
                    if (zone.type == ZoneType.SAFE_ZONE) "Safe Zone • Alert on Exit" else "Restricted Area • Alert on Entry",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("${zone.radius.toInt()}m radius", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
            }
        }
    }
}

@Composable
fun AddZoneDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Double, Float, ZoneType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("100") }
    var type by remember { mutableStateOf(ZoneType.SAFE_ZONE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Zone") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Zone Name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Lat") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(value = lng, onValueChange = { lng = it }, label = { Text("Lng") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = radius, onValueChange = { radius = it }, label = { Text("Radius (meters)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text("Zone Type:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = type == ZoneType.SAFE_ZONE, onClick = { type = ZoneType.SAFE_ZONE })
                    Text("Safe Zone")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(selected = type == ZoneType.RESTRICTED_AREA, onClick = { type = ZoneType.RESTRICTED_AREA })
                    Text("Restricted")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    val dLat = lat.toDoubleOrNull() ?: 0.0
                    val dLng = lng.toDoubleOrNull() ?: 0.0
                    val fRad = radius.toFloatOrNull() ?: 100f
                    if (name.isNotEmpty() && dLat != 0.0) {
                        onConfirm(name, dLat, dLng, fRad, type)
                    }
                },
                enabled = name.isNotEmpty()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
