package com.example.safe_kids.auth

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task

class GoogleSignInHelper(
    private val activity: ComponentActivity,
    private val onSignInResult: (Result<String>) -> Unit
) {
    private lateinit var googleSignInClient: GoogleSignInClient
    private val signInLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Always try to get the account, even if resultCode is cancelled,
            // because the exception usually contains the real error code (e.g. 12500).
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        }
    
    fun initialize() {
        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(AuthConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()
        
        googleSignInClient = GoogleSignIn.getClient(activity, gso)
    }
    
    fun signIn() {
        try {
            val signInIntent = googleSignInClient.signInIntent
            signInLauncher.launch(signInIntent)
        } catch (e: Exception) {
            onSignInResult(Result.failure(e))
        }
    }
    
    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            val idToken = account.idToken
            
            android.util.Log.d("GoogleSignInHelper", "Google Sign-In success. ID Token length: ${idToken?.length ?: 0}")
            
            if (idToken != null) {
                onSignInResult(Result.success(idToken))
            } else {
                onSignInResult(Result.failure(Exception("Failed to get ID token from Google account")))
            }
        } catch (e: ApiException) {
            val statusCode = e.statusCode
            val statusMessage = com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.getStatusCodeString(statusCode)
            android.util.Log.e("GoogleSignInHelper", "Google Sign-In ApiException: $statusCode ($statusMessage)")
            onSignInResult(Result.failure(Exception("Google Sign-In failed: $statusCode ($statusMessage)")))
        } catch (e: Exception) {
            android.util.Log.e("GoogleSignInHelper", "Google Sign-In unexpected error", e)
            onSignInResult(Result.failure(e))
        }
    }
    
    suspend fun signOut() {
        try {
            googleSignInClient.signOut()
        } catch (e: Exception) {
            // Handle sign out error
        }
    }
}
