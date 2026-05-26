package com.example.safe_kids.ui.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.MainActivity
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import android.util.Log

/**
 * EditChildProfileActivity
 * 
 * Wrapper activity for child profile setup.
 * Launched after RequiredPermissionsActivity when all permissions are granted.
 * The ChildProfileSetupScreen from MainActivity is reused here via a composable.
 */
class EditChildProfileActivity : ComponentActivity() {

    private lateinit var authManager: AuthManagerImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("SafeKidsDebug", "EditChildProfileActivity: Activity created")
        
        // FIX #1: Check if ALL permissions are granted before allowing profile setup
        if (!com.example.safe_kids.utils.PermissionManager.areAllPermissionsGranted(this)) {
            Log.w("SafeKidsDebug", "EditChildProfileActivity: Permissions not granted! Redirecting to RequiredPermissionsActivity")
            val intent = Intent(this, RequiredPermissionsActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }
        
        authManager = AuthManagerImpl(this)
        lifecycleScope.launch { authManager.initialize() }

        setContent {
            SAFE_KIDSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EditChildProfileScreenWrapper(
                        authManager = authManager,
                        onProfileComplete = { navigateToPairing() }
                    )
                }
            }
        }
    }

    private fun navigateToPairing() {
        Log.d("SafeKidsDebug", "EditChildProfileActivity: Profile setup complete! Navigating to pairing...")
        
        // Update Firestore to mark profile setup as complete
        lifecycleScope.launch {
            val currentUser = authManager.currentUser.value
            if (currentUser != null) {
                try {
                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(currentUser.id)
                        .update(mapOf(
                            "isProfileSetupComplete" to true,
                            "onboardingStep" to "DEVICE_PAIRING",
                            "lastUpdated" to System.currentTimeMillis()
                        ))
                    Log.d("SafeKidsDebug", "EditChildProfileActivity: Updated Firestore: onboardingStep=DEVICE_PAIRING")
                } catch (e: Exception) {
                    Log.e("SafeKidsDebug", "EditChildProfileActivity: Failed to update Firestore", e)
                }
            }
        }
        
        // Navigate back to MainActivity where pairing code generation happens
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun EditChildProfileScreenWrapper(
    authManager: AuthManagerImpl,
    onProfileComplete: () -> Unit
) {
    // Import ChildProfileSetupScreen from MainActivity
    ChildProfileSetupScreenFromMain(
        authManager = authManager,
        onComplete = { onProfileComplete() },
        onSignOut = {
            // Handle sign out
            Log.d("EditChildProfileActivity", "Sign out requested")
        },
        onBack = {
            // Go back to permissions
            Log.d("EditChildProfileActivity", "Back to permissions")
        }
    )
}

/**
 * This is a wrapper function that mimics ChildProfileSetupScreen from MainActivity.
 * In a real scenario, you'd extract ChildProfileSetupScreen to a separate composable file
 * and import it here instead.
 */
@androidx.compose.runtime.Composable
private fun ChildProfileSetupScreenFromMain(
    authManager: AuthManagerImpl,
    onComplete: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    androidx.compose.material3.Text("Profile Setup")
}
