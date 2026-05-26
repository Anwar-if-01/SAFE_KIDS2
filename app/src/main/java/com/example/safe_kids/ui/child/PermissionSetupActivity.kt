package com.example.safe_kids.ui.child

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.MainActivity
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.services.DeviceAdminService
import com.example.safe_kids.services.ForegroundProtectionService
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.utils.PermissionUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PermissionSetupActivity : ComponentActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var authManager: AuthManagerImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getSharedPreferences("safe_kids_prefs", Context.MODE_PRIVATE)
        authManager = AuthManagerImpl(this)

        // Check if setup is already complete or if user is a parent
        val setupComplete = sharedPreferences.getBoolean("setupComplete", false)
        val userRole = authManager.currentUser.value?.role

        if (setupComplete || userRole == com.example.safe_kids.auth.UserRole.PARENT) {
            navigateToMainApp(true)
            return
        }

        setContent {
            SAFE_KIDSTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionSetupScreen(onSetupComplete = { navigateToMainApp(true) })
                }
            }
        }
    }

    private fun navigateToMainApp(setupCompleted: Boolean) {
        lifecycleScope.launch {
            if (setupCompleted) {
                val currentUser = authManager.currentUser.value
                if (currentUser?.role == com.example.safe_kids.auth.UserRole.CHILD) {
                    authManager.updateUserProfile(mapOf(
                        "isOnboardingComplete" to true,
                        "isPaired" to true,
                        "onboardingStep" to "COMPLETED"
                    ))
                }
                sharedPreferences.edit().putBoolean("setupComplete", true).apply()
                // Start ForegroundProtectionService after setup is complete
                val serviceIntent = Intent(this@PermissionSetupActivity, ForegroundProtectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startService(serviceIntent)
                }
            }

            val intent = Intent(this@PermissionSetupActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSetupScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    var currentStep by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val authManager = remember { AuthManagerImpl(context) }
    val currentUser = authManager.currentUser.collectAsState().value

    // State to track individual permission statuses
    val accessibilityGranted = remember { mutableStateOf(false) }
    val overlayGranted = remember { mutableStateOf(false) }
    val usageAccessGranted = remember { mutableStateOf(false) }
    val dndAccessGranted = remember { mutableStateOf(false) }
    val notificationAccessGranted = remember { mutableStateOf(false) }
    val batteryOptimizationIgnored = remember { mutableStateOf(false) }
    val deviceAdminActive = remember { mutableStateOf(false) }
    val runtimePermissionsGranted = remember { mutableStateOf(false) }

    // Helper function to update all statuses
    val updateAllPermissionStatuses: () -> Unit = {
        accessibilityGranted.value = PermissionUtils.isAccessibilityServiceEnabled(context)
        overlayGranted.value = Settings.canDrawOverlays(context)
        usageAccessGranted.value = PermissionUtils.isUsageStatsPermissionGranted(context)
        dndAccessGranted.value = PermissionUtils.hasNotificationPolicyAccess(context)
        notificationAccessGranted.value = PermissionUtils.isNotificationListenerEnabled(context)
        batteryOptimizationIgnored.value = PermissionUtils.isIgnoringBatteryOptimizations(context)
        deviceAdminActive.value = PermissionUtils.isDeviceAdminActive(context)
        runtimePermissionsGranted.value = PermissionUtils.hasAllRuntimePermissions(context)
    }

    // Initial status update and subsequent updates on resume
    LaunchedEffect(Unit) { updateAllPermissionStatuses() }

    // Use LifecycleEventObserver for ON_RESUME
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateAllPermissionStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live permission status monitoring - write to Firestore children/{childId}/permissionStatus
    LaunchedEffect(
        accessibilityGranted.value, overlayGranted.value, usageAccessGranted.value,
        dndAccessGranted.value, notificationAccessGranted.value, batteryOptimizationIgnored.value,
        deviceAdminActive.value, runtimePermissionsGranted.value
    ) {
        currentUser?.let { user ->
            val db = FirebaseFirestore.getInstance()
            val permissionStatusMap = mapOf(
                "accessibilityEnabled" to accessibilityGranted.value,
                "overlayPermission" to overlayGranted.value,
                "usageAccess" to usageAccessGranted.value,
                "doNotDisturbAccess" to dndAccessGranted.value,
                "notificationListenerEnabled" to notificationAccessGranted.value,
                "batteryOptimizationIgnored" to batteryOptimizationIgnored.value,
                "deviceAdminActive" to deviceAdminActive.value,
                "locationPermission" to (PermissionUtils.hasLocationPermissions(context) && PermissionUtils.hasBackgroundLocationPermission(context)),
                "cameraPermission" to PermissionUtils.hasCameraPermission(context),
                "callLogPermission" to PermissionUtils.hasCallLogPermission(context),
                "smsPermission" to PermissionUtils.hasSmsPermissions(context)
            )
            db.collection("children").document(user.id).collection("permissionStatus").document("live")
                .set(permissionStatusMap)
                .addOnSuccessListener { Log.d("PermissionSetup", "Live permission status updated") }
                .addOnFailureListener { e -> Log.e("PermissionSetup", "Error updating live permission status", e) }
        }
    }

    // 8-step permission setup
    val steps = remember {
        listOf(
            // Step 1 - Accessibility
            PermissionSetupStep(
                title = "Accessibility Access",
                icon = Icons.Default.Accessibility,
                explanation = "Allows SafeKids to monitor which apps are opened and enforce restrictions. This is required for app blocking, screen time limits, and usage tracking.",
                intentAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
                isDataUri = false,
                isGranted = { accessibilityGranted.value },
                requestAction = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            ),
            // Step 2 - Draw Over Other Apps
            PermissionSetupStep(
                title = "Draw Over Other Apps",
                icon = Icons.Default.PictureInPicture,
                explanation = "Allows SafeKids to show block screens on top of other apps when a restricted app is opened.",
                intentAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                isDataUri = true,
                isGranted = { overlayGranted.value },
                requestAction = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            ),
            // Step 3 - Usage Access
            PermissionSetupStep(
                title = "Usage Access",
                icon = Icons.Default.BarChart,
                explanation = "Tracks how long each app is used daily so parents can view usage reports and set screen time limits.",
                intentAction = Settings.ACTION_USAGE_ACCESS_SETTINGS,
                isDataUri = false,
                isGranted = { usageAccessGranted.value },
                requestAction = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            ),
            // Step 4 - Do Not Disturb Access
            PermissionSetupStep(
                title = "Do Not Disturb Access",
                icon = Icons.Default.DoNotDisturb,
                explanation = "Silences notifications during Sleep Mode and Study Mode so your child can rest or focus without distractions.",
                intentAction = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                isDataUri = false,
                isGranted = { dndAccessGranted.value },
                requestAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            ),
            // Step 5 - Notification Access
            PermissionSetupStep(
                title = "Notification Access",
                icon = Icons.Default.Notifications,
                explanation = "Allows SafeKids to read and monitor notifications for content safety monitoring and forwarding alerts to parents.",
                intentAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                isDataUri = false,
                isGranted = { notificationAccessGranted.value },
                requestAction = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            ),
            // Step 6 - Battery Optimization
            PermissionSetupStep(
                title = "Ignore Battery Optimization",
                icon = Icons.Default.BatteryFull,
                explanation = "Prevents Android from stopping SafeKids background service. Required for continuous protection even when the phone is idle.",
                intentAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                isDataUri = true,
                isGranted = { batteryOptimizationIgnored.value },
                requestAction = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            ),
            // Step 7 - Device Admin
            PermissionSetupStep(
                title = "Device Administrator",
                icon = Icons.Default.AdminPanelSettings,
                explanation = "Prevents uninstall of SafeKids without parent permission. This is an extra layer of protection against tampering.",
                intentAction = DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,
                isDataUri = false,
                isGranted = { deviceAdminActive.value },
                requestAction = {
                    val componentName = ComponentName(context, DeviceAdminService::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "SafeKids needs device administrator permission to prevent unauthorized uninstallation.")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            ),
            // Step 8 - Runtime Permissions (all at once)
            PermissionSetupStep(
                title = "App Permissions",
                icon = Icons.Default.Security,
                explanation = "Grant essential permissions: Location, Camera, Call Log, SMS, Notifications, and Storage. These are needed for full monitoring and safety features.",
                intentAction = null,
                isDataUri = false,
                isGranted = { runtimePermissionsGranted.value },
                requestAction = {
                    // Request all runtime permissions at once
                    val permissionsToRequest = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.CAMERA
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    // Use ActivityResultLauncher
                }
            )
        )
    }

    val multiplePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        runtimePermissionsGranted.value = permissions.entries.all { it.value }
        if (runtimePermissionsGranted.value) {
            Toast.makeText(context, "Runtime Permissions Granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Some Runtime Permissions Denied", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Permission Setup") }) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Progress Bar
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = (currentStep + 1).toFloat() / steps.size.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Step ${currentStep + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current Step Content
            val currentPermissionStep = steps[currentStep]
            Crossfade(targetState = currentStep, animationSpec = tween(500)) { stepIndex ->
                StepContent(steps[stepIndex])
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isCurrentStepGranted = currentPermissionStep.isGranted()

                if (currentStep > 0) {
                    OutlinedButton(onClick = { currentStep-- }) {
                        Text("Previous")
                    }
                }

                if (isCurrentStepGranted) {
                    Button(
                        onClick = {
                            if (currentStep < steps.size - 1) {
                                currentStep++
                            } else {
                                onSetupComplete()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(if (currentStep == steps.size - 1) "Finish Setup" else "Next")
                    }
                } else {
                    Button(
                        onClick = {
                            if (currentPermissionStep.intentAction != null) {
                                // Launch settings screen
                                currentPermissionStep.requestAction()
                            } else {
                                // Request runtime permissions
                                val permissionsToRequest = mutableListOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.READ_CALL_LOG,
                                    Manifest.permission.READ_SMS,
                                    Manifest.permission.SEND_SMS,
                                    Manifest.permission.CAMERA
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
                                } else {
                                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                }
                                multiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                            }
                            scope.launch {
                                delay(500)
                                updateAllPermissionStatuses()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Enable Now")
                    }
                }
            }
        }
    }
}

data class PermissionSetupStep(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val explanation: String,
    val intentAction: String?,
    val isDataUri: Boolean,
    val isGranted: () -> Boolean,
    val requestAction: () -> Unit
)

@Composable
fun StepContent(step: PermissionSetupStep) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Icon based on status
        val icon = if (step.isGranted()) Icons.Default.CheckCircle else step.icon
        val tint = if (step.isGranted()) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary

        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(64.dp))

        Text(
            text = step.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = step.explanation,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        AnimatedVisibility(visible = !step.isGranted()) {
            Surface(
                color = Color(0xFFFFF3E0),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Tap 'Enable Now' to grant this permission.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE65100),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        AnimatedVisibility(visible = step.isGranted()) {
            Surface(
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "✓ Permission granted! You can now proceed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF2E7D32),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
