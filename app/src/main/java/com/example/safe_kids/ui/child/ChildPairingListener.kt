package com.example.safe_kids.ui.child

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.safe_kids.auth.AuthConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * ChildPairingListener
 * 
 * Monitors Firestore for pairing completion and automatically navigates
 * child device to ChildDashboardActivity when pairing is detected.
 * 
 * This solves Requirement #2: Child dashboard does not open after pairing
 */
object ChildPairingListener {
    
    private const val TAG = "ChildPairingListener"
    private var listenerRegistration: ListenerRegistration? = null
    
    /**
     * Start listening for pairing completion
     * When isPaired becomes true, automatically navigate to ChildDashboardActivity
     */
    fun startListening(context: Context, childId: String) {
        Log.d(TAG, "Starting pairing listener for childId: $childId")
        
        // Remove any existing listener
        stopListening()
        
        // Listen to users/{childId} for parentIds changes
        listenerRegistration = FirebaseFirestore.getInstance()
            .collection(AuthConfig.USERS_COLLECTION)
            .document(childId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Pairing listener error", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val parentIds = snapshot.get("parentIds") as? List<*>
                    val isPaired = !parentIds.isNullOrEmpty()
                    val onboardingStep = snapshot.getString("onboardingStep") ?: ""
                    
                    Log.d(TAG, "Pairing status update: isPaired=$isPaired, parentIds=$parentIds, onboardingStep=$onboardingStep")
                    
                    // Pairing is complete when parentIds is populated (parent successfully linked child)
                    // onboardingStep transitions to PERMISSIONS after parent uses the code
                    if (isPaired && onboardingStep == "PERMISSIONS") {
                        Log.d(TAG, "Pairing detected! Parent linked child. Navigating to ChildDashboardActivity")
                        
                        // Navigate to Child Dashboard
                        val intent = Intent(context, ChildModeActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        context.startActivity(intent)
                        
                        // Stop listening after successful navigation
                        stopListening()
                    }
                }
            }
    }
    
    /**
     * Stop listening for pairing updates
     */
    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
        Log.d(TAG, "Pairing listener stopped")
    }
}
