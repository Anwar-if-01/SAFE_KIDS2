package com.example.safe_kids.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.MainActivity
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.auth.GoogleSignInHelper
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import kotlinx.coroutines.launch

class AuthActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl
    private lateinit var googleSignInHelper: GoogleSignInHelper
    private var isGoogleLoading by mutableStateOf(false)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Removed enableEdgeToEdge() for Android 12 compatibility
        
        // Role selection is now handled post-authentication in MainActivity
        
        authManager = AuthManagerImpl(this)
        
        // Initialize Google Sign-In helper
        googleSignInHelper = GoogleSignInHelper(this) { result ->
            lifecycleScope.launch {
                android.util.Log.d("AuthActivity", "Google Sign-In result received. Success: ${result.isSuccess}")
                if (result.isSuccess) {
                    val idToken = result.getOrNull()!!
                    val authResult = authManager.handleGoogleSignInResult(idToken)
                    android.util.Log.d("AuthActivity", "Firebase handle result. Success: ${authResult.isSuccess}")
                    
                    isGoogleLoading = false // Reset loading state
                    
                    if (authResult.isSuccess) {
                        // Get user role and redirect to appropriate dashboard
                        val user = authManager.getCurrentUser()
                        android.util.Log.d("AuthActivity", "Google Sign-In user role: ${user?.role}")
                        
                        if (user != null) {
                            // Always go to MainActivity first to handle onboarding/redirection logic
                            startActivity(Intent(this@AuthActivity, MainActivity::class.java))
                            finish()
                        } else {
                            // Fallback to MainActivity
                            startActivity(Intent(this@AuthActivity, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        Toast.makeText(
                            this@AuthActivity, 
                            authResult.exceptionOrNull()?.message ?: "Google Sign-In failed", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    isGoogleLoading = false // Reset loading state
                    val exception = result.exceptionOrNull()
                    android.util.Log.e("AuthActivity", "Google Sign-In failed: ${exception?.message}", exception)
                    Toast.makeText(
                        this@AuthActivity, 
                        exception?.message ?: "Google Sign-In failed", 
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        
        googleSignInHelper.initialize()
        
        // Initialize the auth manager
        lifecycleScope.launch {
            authManager.initialize()
        }
        
        // isGoogleLoading is now a class property
        
        // Handle incoming action from onboarding
        val action = intent.getStringExtra("ACTION")
        
        setContent {
            SAFE_KIDSTheme {
                var initialSignUp = remember { action == "SIGN_UP" }
                
                // Trigger auto Google Sign-In if requested
                LaunchedEffect(action) {
                    if (action == "GOOGLE_SIGN_IN") {
                        isGoogleLoading = true
                        googleSignInHelper.signIn()
                    }
                }

                AuthScreen(
                    authManager = authManager,
                    isLoadingExternal = isGoogleLoading,
                    initialIsSignUp = initialSignUp,
                    onAuthSuccess = {
                        startActivity(Intent(this@AuthActivity, MainActivity::class.java))
                        finish()
                    },
                    onShowError = { message ->
                        isGoogleLoading = false
                        Toast.makeText(this@AuthActivity, message, Toast.LENGTH_LONG).show()
                    },
                    onGoogleSignIn = {
                        isGoogleLoading = true
                        googleSignInHelper.signIn()
                    }
                )
            }
        }
    }
}

@Composable
fun AuthScreen(
    authManager: AuthManagerImpl,
    isLoadingExternal: Boolean = false,
    initialIsSignUp: Boolean = false,
    onAuthSuccess: () -> Unit,
    onShowError: (String) -> Unit,
    onGoogleSignIn: () -> Unit
) {
    var isSignUp by remember { mutableStateOf(initialIsSignUp) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoadingInternal by remember { mutableStateOf(false) }
    val isLoading = isLoadingInternal || isLoadingExternal
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
            // App Logo and Title
            Text(
                text = "🛡️",
                style = MaterialTheme.typography.displayLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Safe Kids",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = if (isSignUp) "Create Account" else "Welcome Back",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Sign Up Fields
            if (isSignUp) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Email Field
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Password Field
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                singleLine = true
            )
            
            // Password Strength Indicator
            if (isSignUp && password.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                PasswordStrengthIndicator(
                    password = password,
                    validationResult = com.example.safe_kids.auth.PasswordValidator.validate(password)
                )
            }
            
            // Confirm Password Field (Sign Up only)
            if (isSignUp) {
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                    singleLine = true
                )
                
                if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                    Text(
                        text = "Passwords don't match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Sign In/Up Button
            Button(
                onClick = {
                    isLoadingInternal = true
                    scope.launch {
                        try {
                            if (isSignUp) {
                                // Validation
                                if (displayName.isBlank()) {
                                    onShowError("Please enter your full name")
                                    return@launch
                                }
                                
                                val validationResult = com.example.safe_kids.auth.PasswordValidator.validate(password)
                                if (!validationResult.isValid) {
                                    onShowError(validationResult.errors.joinToString("; "))
                                    return@launch
                                }
                                
                                if (password != confirmPassword) {
                                    onShowError("Passwords don't match")
                                    return@launch
                                }
                                
                                val result = authManager.signUp(email, password, UserRole.UNDETERMINED, displayName)
                                android.util.Log.d("AuthActivity", "Sign up result: ${result.isSuccess}")
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Account created successfully!", Toast.LENGTH_SHORT).show()
                                    onAuthSuccess()
                                } else {
                                    val error = result.exceptionOrNull()?.message ?: "Sign up failed"
                                    android.util.Log.e("AuthActivity", "Sign up failed: $error")
                                    onShowError(error)
                                }
                            } else {
                                val result = authManager.signInWithEmail(email, password)
                                android.util.Log.d("AuthActivity", "Sign in result: ${result.isSuccess}")
                                if (result.isSuccess) {
                                    Toast.makeText(context, "Signed in successfully!", Toast.LENGTH_SHORT).show()
                                    onAuthSuccess()
                                } else {
                                    val error = result.exceptionOrNull()?.message ?: "Sign in failed"
                                    android.util.Log.e("AuthActivity", "Sign in failed: $error")
                                    onShowError(error)
                                }
                            }
                        } catch (e: Exception) {
                            onShowError(e.message ?: "Authentication failed")
                        } finally {
                            isLoadingInternal = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoading && email.isNotEmpty() && password.isNotEmpty()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (isSignUp) "Create Account" else "Sign In")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Google Sign In Button
            OutlinedButton(
                onClick = {
                    onGoogleSignIn()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !isLoading
            ) {
                Text("🔍 Continue with Google")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Toggle Sign In/Up
            TextButton(
                onClick = { 
                    isSignUp = !isSignUp
                    password = ""
                    confirmPassword = ""
                }
            ) {
                Text(
                    if (isSignUp) "Already have an account? Sign In" 
                    else "Don't have an account? Sign Up"
                )
            }
            
            // Forgot Password (Sign In only)
            if (!isSignUp) {
                TextButton(
                    onClick = {
                        if (email.isNotEmpty()) {
                            scope.launch {
                                try {
                                    val result = authManager.resetPassword(email)
                                    if (result.isSuccess) {
                                        onShowError("Password reset email sent to $email")
                                    } else {
                                        onShowError(result.exceptionOrNull()?.message ?: "Failed to send reset email")
                                    }
                                } catch (e: Exception) {
                                    onShowError(e.message ?: "Failed to send reset email")
                                }
                            }
                        } else {
                            onShowError("Please enter your email first")
                        }
                    }
                ) {
                    Text("Forgot Password?")
                }
            }


        }
    }
}
}

fun getDebugSHA1(context: Context): String {
    try {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNATURES
        )
        if (info.signatures != null) {
            for (signature in info.signatures) {
                val md = MessageDigest.getInstance("SHA-1")
                md.update(signature.toByteArray())
                val digest = md.digest()
                val hexString = StringBuilder()
                for (b in digest) {
                    hexString.append(String.format("%02X:", b))
                }
                if (hexString.length > 0) {
                    hexString.setLength(hexString.length - 1)
                }
                return hexString.toString()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return "Error: ${e.message}"
    }
    return "No signature found"
}

@Composable
fun PasswordStrengthIndicator(
    password: String,
    validationResult: com.example.safe_kids.auth.PasswordValidationResult
) {
    val color = when (validationResult.strength) {
        com.example.safe_kids.auth.PasswordStrength.VERY_WEAK -> MaterialTheme.colorScheme.error
        com.example.safe_kids.auth.PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
        com.example.safe_kids.auth.PasswordStrength.FAIR -> MaterialTheme.colorScheme.tertiary
        com.example.safe_kids.auth.PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
        com.example.safe_kids.auth.PasswordStrength.VERY_STRONG -> MaterialTheme.colorScheme.primary
    }
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Password Strength: ${validationResult.strength.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        LinearProgressIndicator(
            progress = (validationResult.strength.score + 1) / 5f,
            modifier = Modifier.fillMaxWidth(),
            color = color
        )
        
        if (!validationResult.isValid && password.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            validationResult.errors.forEach { error ->
                Text(
                    text = "• $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

