package com.example.safe_kids.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.tasks.await
import android.content.Context
import android.content.Intent
import com.example.safe_kids.MainActivity
import com.example.safe_kids.auth.AuthManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.safe_kids.auth.SessionManager
import com.example.safe_kids.ui.auth.AuthActivity

object AuthUtils {
    /**
     * Globally handles sign out:
     * 1. Clears Firebase Auth session
     * 2. Clears all local session state (role, onboarding flags, pairing state)
     * 3. Redirects to MainActivity with flags to clear the activity stack
     */
    fun signOutAndRedirect(context: Context, authManager: AuthManagerImpl, scope: CoroutineScope) {
        scope.launch {
            // 1. Sign out from Firebase and clear SessionManager
            authManager.signOut()
            
            // 2. Navigate back to MainActivity (Onboarding/Auth entry point)
            withContext(Dispatchers.Main) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
                
                // 3. Ensure current activity is finished if possible
                if (context is android.app.Activity) {
                    context.finish()
                }
            }
        }
    }

    private var isSigningOut = false

    suspend fun forceSignOut(context: Context) {
        if (isSigningOut) return
        isSigningOut = true
        android.util.Log.d("AuthDebug", "forceSignOut CALLED")
        try {
            FirebaseAuth.getInstance().signOut()
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(context, gso).signOut().await()
        } catch (e: Exception) {
            // ignore errors during sign out
        }
        // Clear session
        SessionManager(context).clearAllState()
        // Navigate to auth
        val intent = Intent(context, AuthActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }
}
