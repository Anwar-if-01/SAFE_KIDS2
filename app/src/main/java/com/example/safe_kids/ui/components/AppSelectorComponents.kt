package com.example.safe_kids.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.example.safe_kids.data.InstalledApp

/** Load app icon from local PackageManager — works for apps installed on THIS device. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, iconBase64: String? = null) {
    val context = LocalContext.current
    
    // 1. Try Base64 from Firestore (Remote)
    val decodedBitmap = remember(iconBase64) {
        iconBase64?.let { com.example.safe_kids.utils.IconUtils.base64ToBitmap(it) }
    }

    if (decodedBitmap != null) {
        Image(
            bitmap = decodedBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        // 2. Try local PackageManager (for system apps or local testing)
        val icon: Drawable? = remember(packageName) {
            try { context.packageManager.getApplicationIcon(packageName) } catch (e: Exception) { null }
        }
        
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = modifier
            )
        } else {
            // Fallback: colored circle with first letter
            val color = remember(packageName) {
                val colors = listOf(0xFF4C66EA, 0xFF43A047, 0xFFE53935, 0xFFFF8F00, 0xFF8E24AA)
                Color(colors[packageName.hashCode().and(0x7FFFFFFF) % colors.size])
            }
            Box(
                modifier = modifier.background(color, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = packageName.substringAfterLast('.').take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAppsSheet(
    installedApps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val selectedPackages = remember { mutableStateListOf<String>() }
    val groupedApps = installedApps.groupBy { it.category }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
        ) {
            Text(
                "Add Apps",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            ) {
                if (groupedApps.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("No apps found", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The child's device hasn't synced its app list yet. Make sure the child device is online and the Safe Kids app is running.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.LightGray
                            )
                        }
                    }
                } else {
                    groupedApps.forEach { (category, apps) ->
                        item {
                            Text(
                                category,
                                modifier = Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(apps) { app ->
                            val isSelected = selectedPackages.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) selectedPackages.remove(app.packageName)
                                        else selectedPackages.add(app.packageName)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        if (isSelected) selectedPackages.remove(app.packageName)
                                        else selectedPackages.add(app.packageName)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                                )
                                Spacer(Modifier.width(10.dp))
                                AppIcon(
                                    packageName = app.packageName,
                                    modifier = Modifier.size(40.dp),
                                    iconBase64 = app.iconBase64
                                )

                                Spacer(Modifier.width(12.dp))
                                Text(app.appName, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.2f))
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = { onConfirm(selectedPackages.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
