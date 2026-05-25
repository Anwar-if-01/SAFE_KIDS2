@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.safe_kids

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.safe_kids.auth.AuthManagerImpl
import com.example.safe_kids.auth.UserRole
import com.example.safe_kids.auth.isParentLike
import com.example.safe_kids.ui.auth.AuthActivity
import com.example.safe_kids.ui.child.ChildModeActivity
import com.example.safe_kids.ui.parent.ParentDashboardActivity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.safe_kids.utils.PermissionUtils
import com.example.safe_kids.ui.theme.SAFE_KIDSTheme
import com.example.safe_kids.R
import android.widget.Toast
import kotlinx.coroutines.launch
import android.provider.Settings
import android.net.Uri
import com.example.safe_kids.services.DeviceAdminService
import android.os.Build
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import java.util.Calendar
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.safe_kids.ui.onboarding.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await


class MainActivity : ComponentActivity() {
    private lateinit var authManager: AuthManagerImpl
    private lateinit var settingsManager: com.example.safe_kids.data.SettingsManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize Firebase Realtime Database with correct regional URL
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .setPersistenceEnabled(true)
        try {
            com.google.firebase.database.FirebaseDatabase.getInstance("https://safe-kids-cf8a6-default-rtdb.europe-west1.firebasedatabase.app")
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Firebase RTDB already initialized or error setting URL", e)
        }
        
        authManager = AuthManagerImpl(this)
        settingsManager = com.example.safe_kids.data.SettingsManager(this)
        
        if (FirebaseAuth.getInstance().currentUser != null) {
            var userWasSignedIn = true

            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                if (auth.currentUser == null && userWasSignedIn) {
                    userWasSignedIn = false
                    android.util.Log.d("AuthDebug", "AuthStateListener: user deleted - forcing sign out")
                    lifecycleScope.launch {
                        com.example.safe_kids.utils.AuthUtils.forceSignOut(this@MainActivity)
                    }
                }
            }

            FirebaseAuth.getInstance().addIdTokenListener(FirebaseAuth.IdTokenListener { auth ->
                if (auth.currentUser == null && userWasSignedIn) {
                    userWasSignedIn = false
                    android.util.Log.d("AuthDebug", "IdTokenListener: user deleted - forcing sign out")
                    lifecycleScope.launch {
                        com.example.safe_kids.utils.AuthUtils.forceSignOut(this@MainActivity)
                    }
                }
            })
        }
        
        // Initialize the auth manager
        lifecycleScope.launch {
            android.util.Log.d("MainActivity", "Initializing AuthManager...")
            val user = FirebaseAuth.getInstance().currentUser
            if (user != null) {
                try {
                    kotlinx.coroutines.withTimeout(5000) {
                        user.reload().await()
                    }
                } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                    com.example.safe_kids.utils.AuthUtils.forceSignOut(this@MainActivity)
                    return@launch
                } catch (e: Exception) {
                    // offline or timeout — keep existing session
                }
            }
            authManager.initialize()
            android.util.Log.d("MainActivity", "AuthManager initialized. IsAuthenticated: ${authManager.isAuthenticated.value}")
        }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@addOnSuccessListener
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .update("fcmToken", token)
        }
        
        setContent {
            val themeMode by settingsManager.themeMode.collectAsState(initial = com.example.safe_kids.data.ThemeMode.SYSTEM)
            val textSize by settingsManager.textSize.collectAsState(initial = 1.0f)
            val displaySize by settingsManager.displaySize.collectAsState(initial = 1.0f)
            val language by settingsManager.language.collectAsState(initial = "en")
            
            // Apply Theme change globally
            LaunchedEffect(themeMode) {
                val mode = when (themeMode) {
                    com.example.safe_kids.data.ThemeMode.LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    com.example.safe_kids.data.ThemeMode.DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    com.example.safe_kids.data.ThemeMode.SYSTEM -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
            
            // Apply Language change
            LaunchedEffect(language) {
                val appLocale: androidx.core.os.LocaleListCompat = androidx.core.os.LocaleListCompat.forLanguageTags(language)
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
            }
            
            val useDarkTheme = when (themeMode) {
                com.example.safe_kids.data.ThemeMode.LIGHT -> false
                com.example.safe_kids.data.ThemeMode.DARK -> true
                com.example.safe_kids.data.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            SAFE_KIDSTheme(
                darkTheme = useDarkTheme,
                textSize = textSize,
                displaySize = displaySize
            ) {
                val currentUserState = authManager.currentUser.collectAsState()
val currentUser = currentUserState.value
                val isAuthenticated by authManager.isAuthenticated.collectAsState()
                val isInitializing by authManager.isInitializing.collectAsState()
                
                // MVVM Onboarding Logic
                val onboardingViewModel: OnboardingViewModel = viewModel(
                    factory = OnboardingViewModelFactory(authManager, this@MainActivity)
                )
                val currentStep by onboardingViewModel.currentStep.collectAsState()
                
                val context = LocalContext.current
                val deviceStateManager = remember { com.example.safe_kids.data.DeviceStateManager(context) }
                val deviceState by deviceStateManager.deviceState.collectAsState(initial = com.example.safe_kids.data.DeviceState())
                
                var showAuthOptions by remember { mutableStateOf(false) }
                var isForcePairing by remember { 
                    mutableStateOf(intent.getBooleanExtra("FORCE_SHOW_PAIRING", false))
                }
                
                var backPressedTime by remember { mutableLongStateOf(0L) }
                
                androidx.activity.compose.BackHandler {
                    if (isForcePairing) {
                        // Return to dashboard if pairing was forced
                        val intent = Intent(context, ParentDashboardActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        context.startActivity(intent)
                        (context as? android.app.Activity)?.finish()
                    } else if (!isAuthenticated && showAuthOptions) {
                        showAuthOptions = false
                    } else {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - backPressedTime < 2000) {
                            (context as? android.app.Activity)?.finishAffinity()
                        } else {
                            backPressedTime = currentTime
                            android.widget.Toast.makeText(context, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                // Sync local state when currentUser changes
                // Sync with Dashboard when pairing is complete
                LaunchedEffect(currentUser) {
                    if (currentUser != null && currentUser?.parentIds?.isNotEmpty() == true) {
                        onboardingViewModel.completePairing()
                        onboardingViewModel.completeProfile()
                        isForcePairing = false
                    }
                }
                
                // Initialize Realtime Database presence for child users
                LaunchedEffect(currentUser) {
                    if (currentUser != null && currentUser.role == UserRole.CHILD) {
                        val database = com.google.firebase.database.FirebaseDatabase.getInstance()
                        val presenceRef = database.getReference("child_presence/${currentUser.id}/isOnline")
                        val connectedRef = database.getReference(".info/connected")
                        
                        connectedRef.addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                val connected = snapshot.getValue(Boolean::class.java) ?: false
                                if (connected) {
                                    presenceRef.setValue(true)
                                    presenceRef.onDisconnect().setValue(false)
                                    
                                    // Also set lastSeen timestamp
                                    database.getReference("child_presence/${currentUser.id}/lastSeen")
                                        .setValue(System.currentTimeMillis())
                                }
                            }
                            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                                android.util.Log.e("MainActivity", "Presence listener cancelled", error.toException())
                            }
                        })
                    }
                }
                
                // Global Redirection logic based on ViewModel Step.
                // IMPORTANT: We must also check that role is not UNDETERMINED — after Sign Out
                // or Switch Mode the sessionManager no longer has a role, so currentUser from
                // the server snapshot may briefly be UNDETERMINED. Redirecting in that state
                // would send the user to the wrong dashboard.
                LaunchedEffect(isAuthenticated, currentUser, currentStep, isForcePairing, deviceState) {
                    if (!isAuthenticated || currentUser == null) return@LaunchedEffect
                    
                    val hasRole = currentUser?.role != null && currentUser?.role != UserRole.UNDETERMINED
                    if (!hasRole) return@LaunchedEffect
                    
                    val isChild = currentUser?.role == UserRole.CHILD
                    val isFullyConfigured = deviceState.isProfileComplete && deviceState.permissionsGranted
                    
                    android.util.Log.d("MainActivity", "Redirection check: role=${currentUser?.role}, isForcePairing=$isForcePairing, isFullyConfigured=$isFullyConfigured")
                    
                    if (isChild) {
                        // Only trigger dashboard redirects if onboarding is complete in the ViewModel
                        if (currentStep == OnboardingStep.COMPLETED) {
                            when {
                                !isFullyConfigured -> {
                                    onboardingViewModel.determineInitialStep()
                                }
                                !deviceState.isBound -> {
                                    onboardingViewModel.determineInitialStep()
                                }
                                else -> {
                                    onboardingViewModel.triggerNavigation(NavigationEvent.NavigateToChildDashboard)
                                }
                            }
                        }
                    } else if ((currentUser?.role == UserRole.PARENT || currentUser?.role == UserRole.CO_PARENT) && !isForcePairing) {
                        // For Parents and Co-Parents, only auto-redirect if onboarding is fully COMPLETED.
                        if (currentStep == OnboardingStep.COMPLETED) {
                            onboardingViewModel.triggerNavigation(NavigationEvent.NavigateToParentDashboard)
                        }
                    }
                }

                // Single Event Navigation Observer
                LaunchedEffect(Unit) {
                    onboardingViewModel.navigationEvents.collect { event ->
                        android.util.Log.d("MainActivity", "Handling NavigationEvent: $event")
                        when (event) {
                            is NavigationEvent.NavigateToParentDashboard -> {
                                val intent = Intent(context, ParentDashboardActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                context.startActivity(intent)
                                (context as? android.app.Activity)?.finish()
                            }
                            is NavigationEvent.NavigateToChildDashboard -> {
                                val intent = Intent(context, ChildModeActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                context.startActivity(intent)
                                (context as? android.app.Activity)?.finish()
                            }
                            is NavigationEvent.NavigateToAuth -> {
                                val intent = Intent(context, AuthActivity::class.java)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                context.startActivity(intent)
                                (context as? android.app.Activity)?.finish()
                            }
                            is NavigationEvent.NavigateToRoleSelection -> {
                                val intent = Intent(context, AuthActivity::class.java) 
                                intent.putExtra("SHOW_ROLE_SELECTION", true)
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                context.startActivity(intent)
                                (context as? android.app.Activity)?.finish()
                            }
                        }
                    }
                }


                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isInitializing) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Starting Safe Kids...")
                            }
                        }
                    } else if (!isAuthenticated) {
                        if (!showAuthOptions) {
                            OnboardingScreen(
                                onContinue = { showAuthOptions = true }
                            )
                        } else {
                            AuthOptionsScreen(
                                onNavigateToGoogle = {
                                    val intent = Intent(this@MainActivity, AuthActivity::class.java)
                                    intent.putExtra("ACTION", "GOOGLE_SIGN_IN")
                                    startActivity(intent)
                                },
                                onNavigateToSignUp = {
                                    val intent = Intent(this@MainActivity, AuthActivity::class.java)
                                    intent.putExtra("ACTION", "SIGN_UP")
                                    startActivity(intent)
                                },
                                onNavigateToSignIn = {
                                    val intent = Intent(this@MainActivity, AuthActivity::class.java)
                                    intent.putExtra("ACTION", "SIGN_IN")
                                    startActivity(intent)
                                },
                                onBack = { showAuthOptions = false }
                            )
                        }
                    } else if (isAuthenticated && currentUser != null) {
                        // Role Selection (Prerequisite for Onboarding)
                        if (currentUser?.role == UserRole.UNDETERMINED) {
                            DeviceRoleSelectionScreen(
                                onRoleSelected = { role ->
                                    lifecycleScope.launch {
                                        authManager.updateUserProfile(mapOf("role" to role.name))
                                    }
                                },
                                onSignOut = {
                                    com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(this@MainActivity, authManager, lifecycleScope)
                                }
                            )
                        } else {
                            // Onboarding Flow: React to ViewModel Steps
                            val effectiveStep = if (isForcePairing) OnboardingStep.DEVICE_PAIRING else currentStep
                            when (effectiveStep) {
                                OnboardingStep.PROFILE_SETUP -> {
                                    if (currentUser?.role == UserRole.CHILD) {
                                        ChildProfileSetupScreen(
                                            authManager = authManager,
                                            onComplete = {
                                                onboardingViewModel.completeProfile()
                                            },
                                            onSignOut = {
                                                com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(this@MainActivity, authManager, lifecycleScope)
                                            },
                                            onBack = {
                                                // Reset role to UNDETERMINED to go back to role selection
                                                lifecycleScope.launch {
                                                    authManager.updateUserProfile(mapOf("role" to UserRole.UNDETERMINED.name))
                                                    onboardingViewModel.triggerNavigation(NavigationEvent.NavigateToRoleSelection)
                                                }
                                            }
                                        )
                                    } else {
                                        LaunchedEffect(currentStep) {
                                            if (currentStep == OnboardingStep.PROFILE_SETUP) {
                                                android.util.Log.d("MainActivity", "Parent detected in Profile Setup. Skipping to Pairing.")
                                                onboardingViewModel.completeProfile()
                                            }
                                        }
                                    }
                                }
                                OnboardingStep.CO_PARENT_INVITE -> {
                                    InviteCodeScreen(
                                        authManager = authManager,
                                        onComplete = {
                                            onboardingViewModel.completePairing()
                                            onboardingViewModel.completeProfile()
                                            lifecycleScope.launch {
                                                authManager.updateUserProfile(mapOf(
                                                    "isOnboardingComplete" to true,
                                                    "isProfileSetupComplete" to true
                                                ))
                                            }
                                        },
                                        onBack = {
                                            lifecycleScope.launch {
                                                authManager.updateUserProfile(mapOf("role" to UserRole.UNDETERMINED.name))
                                                onboardingViewModel.triggerNavigation(NavigationEvent.NavigateToRoleSelection)
                                            }
                                        }
                                    )
                                }
                                OnboardingStep.DEVICE_PAIRING -> {
                                    if (currentUser?.role?.isParentLike == true) {
                                        ParentPairingScreen(
                                            authManager = authManager,
                                            onComplete = {
                                                onboardingViewModel.completePairing()
                                                isForcePairing = false
                                            },
                                            onSignOut = {
                                                com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(this@MainActivity, authManager, lifecycleScope)
                                            }
                                        )
                                    } else {
                                        BindingCodeScreen(
                                            authManager = authManager,
                                            onComplete = {
                                                onboardingViewModel.completePairing()
                                                onboardingViewModel.completeProfile()
                                                isForcePairing = false
                                                lifecycleScope.launch {
                                                    authManager.updateUserProfile(mapOf(
                                                        "isOnboardingComplete" to true,
                                                        "isProfileSetupComplete" to true
                                                    ))
                                                }
                                            },
                                            onSignOut = {
                                                com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(this@MainActivity, authManager, lifecycleScope)
                                            }
                                        )
                                    }
                                }
                                OnboardingStep.PERMISSIONS -> {
                                    PermissionOnboardingScreen(
                                        role = currentUser?.role ?: UserRole.CHILD,
                                        onComplete = {
                                            onboardingViewModel.completePermissions()
                                        },
                                        onSignOut = {
                                            com.example.safe_kids.utils.AuthUtils.signOutAndRedirect(this@MainActivity, authManager, lifecycleScope)
                                        }
                                    )
                                }
                                OnboardingStep.COMPLETED -> {
                                    // Redirection LaunchedEffect will handle this
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator()
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }
    }
}

data class OnboardingSlide(
    val imageRes: Int,
    val title: String,
    val description: String
)

@Composable
fun OnboardingScreen(
    onContinue: () -> Unit
) {
    val slides = listOf(
        OnboardingSlide(
            imageRes = R.drawable.onboarding_monitoring,
            title = "Remotely Monitor Your\nChild's Device",
            description = "Remotely view screen activity and check surroundings via camera and microphone of the child's device."
        ),
        OnboardingSlide(
            imageRes = R.drawable.onboarding_location,
            title = "Real-time Location\nTracking",
            description = "Keep track of your child's real-time location and visit history for peace of mind."
        ),
        OnboardingSlide(
            imageRes = R.drawable.onboarding_limits,
            title = "Set App Usage\nLimits",
            description = "Manage screen time by setting daily limits for specific apps and categories."
        )
    )

    val context = LocalContext.current
    val settingsManager = remember { com.example.safe_kids.data.SettingsManager(context) }
    val scope = rememberCoroutineScope()

    // Persist & read agreement state via DataStore
    val termsAgreedFlow by settingsManager.termsAgreed.collectAsState(initial = false)
    var termsChecked by remember { mutableStateOf(false) }
    var showAgreementError by remember { mutableStateOf(false) }

    // Sync checkbox with persisted value on first composition
    LaunchedEffect(termsAgreedFlow) {
        if (termsAgreedFlow) termsChecked = true
    }

    // Which legal document to show (null = none)
    var legalDocument by remember { mutableStateOf<Pair<String, String>?>(null) }

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val isLastSlide = pagerState.currentPage == slides.size - 1

    // If a legal doc was requested, show it as a full-screen overlay
    if (legalDocument != null) {
        val (docTitle, docAsset) = legalDocument!!
        LegalDocumentScreen(
            title = docTitle,
            assetPath = docAsset,
            onBack = { legalDocument = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Content (Pager)
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            OnboardingSlideContent(slides[page])
        }

        // Page Indicator
        Row(
            Modifier
                .height(40.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(slides.size) { iteration ->
                val color = if (pagerState.currentPage == iteration) Color(0xFF5C6BC0) else Color.LightGray
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(color)
                        .size(8.dp)
                )
            }
        }

        // ─── Terms & Privacy checkbox — only visible on the last slide ─────────
        androidx.compose.animation.AnimatedVisibility(visible = isLastSlide) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = termsChecked,
                        onCheckedChange = { checked ->
                            termsChecked = checked
                            showAgreementError = false
                            // Persist immediately when checked
                            if (checked) {
                                scope.launch { settingsManager.setTermsAgreed(true) }
                            } else {
                                scope.launch { settingsManager.setTermsAgreed(false) }
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF5C6BC0)
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Annotated clickable text with ToS and PP links
                    val annotatedText = buildAnnotatedString {
                        append("I agree to the ")
                        pushStringAnnotation(tag = "TERMS", annotation = "terms")
                        withStyle(SpanStyle(color = Color(0xFF5C6BC0), fontWeight = FontWeight.Bold)) {
                            append("Terms of Service")
                        }
                        pop()
                        append(" and ")
                        pushStringAnnotation(tag = "PRIVACY", annotation = "privacy")
                        withStyle(SpanStyle(color = Color(0xFF5C6BC0), fontWeight = FontWeight.Bold)) {
                            append("Privacy Policy")
                        }
                        pop()
                    }

                    androidx.compose.foundation.text.ClickableText(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF333333)
                        ),
                        onClick = { offset ->
                            annotatedText.getStringAnnotations("TERMS", offset, offset)
                                .firstOrNull()?.let {
                                    legalDocument = "Terms of Service" to "terms.html"
                                }
                            annotatedText.getStringAnnotations("PRIVACY", offset, offset)
                                .firstOrNull()?.let {
                                    legalDocument = "Privacy Policy" to "privacy.html"
                                }
                        }
                    )
                }

                // Validation message
                if (showAgreementError) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Please agree to the Terms of Service and Privacy Policy to continue.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 48.dp)
                    )
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────────

        Spacer(modifier = Modifier.height(16.dp))

        // Continue Button Section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(min = 56.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isLastSlide
            ) {
                Button(
                    onClick = {
                        if (!termsChecked) {
                            showAgreementError = true
                        } else {
                            showAgreementError = false
                            onContinue()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (termsChecked) Color(0xFF5C6BC0) else Color(0xFFB0BEC5)
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AuthOptionsScreen(
    onNavigateToGoogle: () -> Unit,
    onNavigateToSignUp: () -> Unit,
    onNavigateToSignIn: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Identity
        Text(text = "🛡️", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Welcome to Safe Kids",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E293B)
        )
        Text(
            text = "Protect your family's digital world",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF64748B)
        )

        Spacer(Modifier.height(64.dp))

        // Google Sign In
        Button(
            onClick = onNavigateToGoogle,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Placeholder for Google Icon
                Text("G", fontWeight = FontWeight.Black, color = Color.Gray, fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Text("Sign in with Google", color = Color(0xFF475569), fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Email Sign Up
        Button(
            onClick = onNavigateToSignUp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Create Account", fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(24.dp))

        // Sign In Link
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onNavigateToSignIn() }
        ) {
            Text("Already have an account? ", color = Color(0xFF64748B))
            Text("Sign In", color = Color(0xFF5C6BC0), fontWeight = FontWeight.Bold)
        }
        
        Spacer(Modifier.height(48.dp))
        
        TextButton(onClick = onBack) {
            Text("← Back to features", color = Color(0xFF94A3B8))
        }
    }
}

@Composable
fun OnboardingSlideContent(slide: OnboardingSlide) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = slide.imageRes),
            contentDescription = null,
            modifier = Modifier
                .height(280.dp)
                .padding(horizontal = 40.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp,
            color = Color(0xFF333333)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = slide.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = Color(0xFF666666),
            modifier = Modifier.padding(horizontal = 48.dp),
            lineHeight = 24.sp
        )
    }
}

@Composable
fun MainScreen(
    authManager: AuthManagerImpl,
    modifier: Modifier = Modifier,
    onNavigateToAuth: () -> Unit,
    onNavigateToMode: (UserRole) -> Unit
) {
    val currentUser by authManager.currentUser.collectAsState()
    val isAuthenticated by authManager.isAuthenticated.collectAsState()
    
    SafeKidsHomeScreen(
        modifier = modifier,
        isAuthenticated = isAuthenticated,
        currentUser = currentUser,
        onNavigateToAuth = onNavigateToAuth,
        onModeSelected = onNavigateToMode
    )
}

@Composable
fun SafeKidsHomeScreen(
    modifier: Modifier = Modifier,
    isAuthenticated: Boolean = false,
    currentUser: com.example.safe_kids.auth.User? = null,
    onNavigateToAuth: () -> Unit,
    onModeSelected: (UserRole) -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
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
            text = "Digital Safety & Wellbeing",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = if (isAuthenticated) "Your assigned mode:" else "Choose your mode:",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (!isAuthenticated || currentUser?.role?.isParentLike == true) {
            ModeSelectionCard(
                title = "Parent Mode",
                description = "Monitor and protect your children's digital activity",
                icon = "👨‍👩‍👧‍👦",
                onClick = { onModeSelected(UserRole.PARENT) }
            )
            
            if (!isAuthenticated) Spacer(modifier = Modifier.height(16.dp))
        }
        
        if (!isAuthenticated || currentUser?.role == UserRole.CHILD) {
            ModeSelectionCard(
                title = "Child Mode",
                description = "Safe and supervised digital experience",
                icon = "👶",
                onClick = { onModeSelected(UserRole.CHILD) }
            )
            
            if (!isAuthenticated) Spacer(modifier = Modifier.height(16.dp))
        }
        

        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!isAuthenticated) {
            OutlinedButton(
                onClick = onNavigateToAuth,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign In / Create Account")
            }
        } else {
            Text(
                text = "Welcome back, ${currentUser?.displayName ?: "User"}!",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Safe Kids helps families manage screen time and stay connected safely.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun OnboardingLayout(
    onSignOut: () -> Unit,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Main Content (Scrollable, keyboard-aware)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp),
            contentAlignment = contentAlignment
        ) {
            content()
        }
        
        // Pinned Top Menu
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            OnboardingTopMenu(onSignOut = onSignOut)
        }
    }
}

@Composable
fun OnboardingTopMenu(onSignOut: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Menu",
                    tint = Color(0xFF64748B)
                )
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color.White)
            ) {
                DropdownMenuItem(
                    text = { Text("Language") },
                    onClick = { 
                        expanded = false
                        Toast.makeText(context, "Language selection coming soon", Toast.LENGTH_SHORT).show()
                    },
                    leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                
                DropdownMenuItem(
                    text = { Text("Sign Out", color = Color.Red) },
                    onClick = { 
                        expanded = false
                        onSignOut()
                    },
                    leadingIcon = { Icon(Icons.Default.Logout, contentDescription = null, tint = Color.Red, modifier = Modifier.size(18.dp)) }
                )
            }
        }
    }
}

// --- Post-Auth Onboarding Screens ---

@Composable
fun DeviceRoleSelectionScreen(
    onRoleSelected: (UserRole) -> Unit,
    onSignOut: () -> Unit
) {
    OnboardingLayout(onSignOut = onSignOut, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Whose device is this?",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(64.dp))
            
            // Mine (Parent) Card
            RoleSelectionCard(
                title = "Parent device",
                description = "I want to protect my family",
                icon = Icons.Default.Person,
                onClick = { onRoleSelected(UserRole.PARENT) }
            )
            
            Spacer(modifier = Modifier.height(20.dp))

            // Co-parent Card
            RoleSelectionCard(
                title = "Join as Co-parent",
                description = "I was invited to help protect",
                icon = Icons.Default.GroupAdd,
                onClick = { onRoleSelected(UserRole.CO_PARENT) }
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Child Card
            RoleSelectionCard(
                title = "Child device",
                description = "I want to protect this child",
                icon = Icons.Default.ChildCare,
                onClick = { onRoleSelected(UserRole.CHILD) }
            )
        }
    }
}

@Composable
fun RoleSelectionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(20.dp))
            
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

private fun openLocationSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildProfileSetupScreen(
    authManager: com.example.safe_kids.auth.AuthManager,
    onComplete: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf<String>("boy1") }
    var customAvatarUri by remember { mutableStateOf<Uri?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    var selectedYear by remember { mutableStateOf(currentYear - 7) }
    var selectedMonth by remember { mutableStateOf(0) } // 0 = Jan
    
    val months = listOf("January", "February", "March", "April", "May", "June", 
                        "July", "August", "September", "October", "November", "December")
    val years = (currentYear downTo currentYear - 18).toList()
    
    // Virtual avatars representing the character types
    val avatars = listOf("boy1", "girl1", "boy2", "girl2", "boy3", "girl3", "boy4")
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                customAvatarUri = uri
                selectedAvatar = "CUSTOM"
            }
        }
    )
    
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFE0E7FF), Color(0xFFF5F3FF), Color(0xFFFFFFFF))
        )
    )) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White)
                    .size(40.dp)
            ) {
                Icon(Icons.Default.ArrowBack, null, tint = Color(0xFF6366F1), modifier = Modifier.size(24.dp))
            }
            
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White)
                        .size(40.dp)
                ) {
                    Icon(Icons.Default.MoreVert, null, tint = Color(0xFF6366F1), modifier = Modifier.size(24.dp))
                }
                
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    DropdownMenuItem(
                        text = { Text("Language") },
                        leadingIcon = { Icon(Icons.Default.Language, null, tint = Color(0xFF6366F1)) },
                        onClick = { 
                            menuExpanded = false
                            Toast.makeText(context, "Language selection coming soon", Toast.LENGTH_SHORT).show()
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                    DropdownMenuItem(
                        text = { Text("Sign Out", color = Color.Red) },
                        leadingIcon = { Icon(Icons.Default.ExitToApp, null, tint = Color.Red) },
                        onClick = { 
                            menuExpanded = false
                            onSignOut()
                        }
                    )
                }
            }
        }

        // Background Landscape (Bottom)
        Box(modifier = Modifier.fillMaxSize()) {
            // Grass and Flowers representation at the bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xFF86EFAC).copy(alpha = 0.3f))
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Spacer(modifier = Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✨", fontSize = 24.sp)
                Text(
                    text = "Edit Child's Profile",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF1E293B)
                )
                Text("✨", fontSize = 24.sp)
            }
            Text(
                text = "Let's create a profile your little one will love ⭐",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Avatar Card
            SetupSectionCard(icon = "🤖", title = "Choose an Avatar") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false
                ) {
                    items(avatars) { avatar ->
                        AvatarItem(
                            content = avatar,
                            isSelected = selectedAvatar == avatar,
                            onClick = { 
                                selectedAvatar = avatar
                                customAvatarUri = null
                            }
                        )
                    }
                    
                    item {
                        AvatarItem(
                            content = if (customAvatarUri != null) customAvatarUri!! else "CUSTOM",
                            isSelected = selectedAvatar == "CUSTOM",
                            onClick = { 
                                imagePickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Name Card
            SetupSectionCard(icon = "👤", title = "Child's Name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Enter child's name") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null, tint = Color(0xFFF472B6)) },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFF472B6),
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    ),
                    isError = name.isEmpty() && isSaving
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Date Card
            val age = currentYear - selectedYear
            SetupSectionCard(icon = "📅", title = "Date of Birth", subtitle = "($age years old)") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Year Selector
                    var yearExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = yearExpanded,
                        onExpandedChange = { yearExpanded = !yearExpanded },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = selectedYear.toString(),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = yearExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = yearExpanded,
                            onDismissRequest = { yearExpanded = false }
                        ) {
                            years.forEach { year ->
                                DropdownMenuItem(
                                    text = { Text(year.toString()) },
                                    onClick = { 
                                        selectedYear = year
                                        yearExpanded = false 
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }

                    // Month Selector
                    var monthExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = monthExpanded,
                        onExpandedChange = { monthExpanded = !monthExpanded },
                        modifier = Modifier.weight(1.2f)
                    ) {
                        OutlinedTextField(
                            value = months[selectedMonth],
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = monthExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier.menuAnchor(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = monthExpanded,
                            onDismissRequest = { monthExpanded = false }
                        ) {
                            months.forEachIndexed { index, month ->
                                DropdownMenuItem(
                                    text = { Text(month) },
                                    onClick = { 
                                        selectedMonth = index
                                        monthExpanded = false 
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            
            // Save Button
            Button(
                onClick = {
                    if (name.isBlank()) {
                        isSaving = false
                        Toast.makeText(context, "Please enter a name", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    scope.launch {
                        isSaving = true
                        try {
                            val birthDate = Calendar.getInstance().apply {
                                set(Calendar.YEAR, selectedYear)
                                set(Calendar.MONTH, selectedMonth)
                                set(Calendar.DAY_OF_MONTH, 1)
                            }.timeInMillis

                            // ----------------------------------------------------------------
                            // Upload custom image to Firebase Storage and get a real https URL.
                            // Preset avatar keys ("boy1", "girl1", etc.) are stored as-is.
                            // ----------------------------------------------------------------
                            val profileImageUrl: String? = when {
                                selectedAvatar == "CUSTOM" && customAvatarUri != null -> {
                                    try {
                                        val uid = FirebaseAuth.getInstance().currentUser?.uid
                                            ?: throw Exception("Not authenticated")
                                        val storageRef = FirebaseStorage.getInstance()
                                            .reference
                                            .child("child_avatars/$uid/profile.jpg")

                                        // Upload the local URI bytes
                                        storageRef.putFile(customAvatarUri!!).await()

                                        // Return the permanent download URL
                                        storageRef.downloadUrl.await().toString()
                                    } catch (e: Exception) {
                                        android.util.Log.e("ChildProfileSetup", "Image upload failed", e)
                                        Toast.makeText(context, "Image upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        null // Save profile without image rather than blocking
                                    }
                                }
                                selectedAvatar != "CUSTOM" -> selectedAvatar // e.g. "boy1", "girl1"
                                else -> null
                            }

                            val updates = mutableMapOf<String, Any>(
                                "displayName" to name,
                                "birthDate" to birthDate,
                                "isProfileSetupComplete" to true
                            )
                            profileImageUrl?.let { updates["profileImageUrl"] = it }

                            val result = authManager.updateUserProfile(updates)
                            isSaving = false
                            if (result.isSuccess) {
                                onComplete()
                            } else {
                                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                                val errorType = result.exceptionOrNull()?.javaClass?.simpleName ?: "Exception"
                                android.util.Log.e("ChildProfileSetup", "Failed to save profile ($errorType): $errorMsg")
                                Toast.makeText(context, "Save Failed ($errorType): $errorMsg", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(0.dp),
                enabled = !isSaving
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color(0xFF818CF8), Color(0xFFC084FC))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⭐", fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            Text("Save Profile", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, null, tint = Color.White)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Pinned Top Menu Sign Out logic is in OnboardingLayout
        }
    }
}

@Composable
fun SetupSectionCard(
    icon: String,
    title: String,
    subtitle: String = "",
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF5F3FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(icon, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B), fontSize = 16.sp)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(subtitle, color = Color(0xFF818CF8), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            content()
        }
    }
}

@Composable
fun AvatarItem(
    content: Any, // String (identifier), Uri
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val backgroundColor = Color.White
    val borderColor = if (isSelected) Color(0xFF818CF8) else Color(0xFFF1F5F9)

    Surface(
        onClick = onClick,
        modifier = Modifier.size(80.dp),
        shape = CircleShape,
        color = backgroundColor,
        border = BorderStroke(if (isSelected) 3.dp else 1.dp, borderColor)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            when (content) {
                "CUSTOM" -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.size(28.dp)
                        )
                        Text("Custom", fontSize = 10.sp, color = Color(0xFF818CF8), fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF818CF8)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                is android.net.Uri -> {
                    // Manual image loader for Uri
                    val bitmap = remember(content) {
                        try {
                            val inputStream = context.contentResolver.openInputStream(content)
                            BitmapFactory.decodeStream(inputStream)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Person, null, tint = Color(0xFF818CF8), modifier = Modifier.size(36.dp))
                    }
                }
                else -> {
                    // Placeholder for character avatars
                    // In a real app, these would be local drawable resources
                    val emoji = when(content) {
                        "boy1" -> "👦"
                        "girl1" -> "👧"
                        "boy2" -> "🧒"
                        "girl2" -> "👩"
                        "boy3" -> "👦"
                        "girl3" -> "👧"
                        "boy4" -> "🧑"
                        else -> "👤"
                    }
                    Text(text = emoji, fontSize = 36.sp)
                }
            }
        }
    }
}

// --- Permission Helper Functions ---

private fun openAccessibilitySettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
    }
}

private fun openOverlaySettings(context: android.content.Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to general settings if specific app uri fails
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}

private fun openUsageAccessSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open Usage Access Settings", Toast.LENGTH_SHORT).show()
    }
}

private fun openNotificationListenerSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Soft-fail: mark as theoretically granted so user can skip
        PermissionUtils.isNotificationListenerNotSupported = true
        Toast.makeText(context, "Notification settings not supported on this device. Permission skipped.", Toast.LENGTH_LONG).show()
    }
}

private fun openAppInfoSettings(context: android.content.Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}

data class PermissionData(
    val name: String,
    val check: (Context) -> Boolean,
    val action: (Context) -> Unit,
    val mandatory: Boolean = true
)

data class PermissionGroupData(
    val title: String,
    val description: String,
    val icon: String,
    val permissions: List<PermissionData>,
    val mandatory: Boolean = true
)

@Composable
fun PermissionOnboardingScreen(
    role: UserRole,
    onComplete: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                refreshTrigger++
            }
        }
    )

    // Fire once on first composition, then again on every resume
    LaunchedEffect(Unit) { refreshTrigger++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dynamically filter permissions: Show only missing ones
    val filteredGroups = remember(role, refreshTrigger) {
        val groups = if (role == UserRole.CHILD) {
            listOf(
                PermissionGroupData(
                    title = "Foundation",
                    description = "Core permissions for app limits and monitoring",
                    icon = "🛡️",
                    permissions = listOf(
                        PermissionData("Accessibility Permission", { PermissionUtils.isAccessibilityServiceEnabled(it) }, { openAccessibilitySettings(it) }),
                        PermissionData("Usage Statistics Access", { PermissionUtils.isUsageStatsPermissionGranted(it) }, { openUsageAccessSettings(it) })
                    )
                ),
                PermissionGroupData(
                    title = "Location Tracking",
                    description = "Real-time location and safety alerts",
                    icon = "📍",
                    permissions = listOf(
                        PermissionData(
                            "Location Permission", 
                            { PermissionUtils.hasLocationPermissions(it) }, 
                            { 
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        ),
                        PermissionData(
                            "Background Location", 
                            { PermissionUtils.hasBackgroundLocationPermission(it) }, 
                            { 
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    Toast.makeText(it, "Please select 'Allow all the time' in the next screen", Toast.LENGTH_LONG).show()
                                    (it as? ComponentActivity)?.let { activity ->
                                        androidx.core.app.ActivityCompat.requestPermissions(
                                            activity, 
                                            arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 
                                            1002
                                        )
                                    }
                                }
                            },
                            mandatory = true
                        )
                    )
                ),

                PermissionGroupData(
                    title = "Battery & Stability",
                    description = "Ensure protection stays active. Please select 'Allow' in the next screen and then return to the app.",
                    icon = "🔋",
                    permissions = listOf(
                        PermissionData(
                            "Ignore Battery Optimizations",
                            { PermissionUtils.isIgnoringBatteryOptimizations(it) },
                            { 
                                Toast.makeText(it, "Redirecting to settings. Please allow background activity, then return here.", Toast.LENGTH_LONG).show()
                                PermissionUtils.requestIgnoreBatteryOptimizations(it) 
                            },
                            mandatory = true
                        )
                    )
                ),

                PermissionGroupData(
                    title = "Visibility & Admin",
                    description = "Prevent uninstallation and show block screens",
                    icon = "📱",
                    permissions = listOf(
                        PermissionData("Display Over Other Apps", { PermissionUtils.canDrawOverlays(it) }, { openOverlaySettings(it) }),
                        PermissionData("Device Admin Access", { PermissionUtils.isDeviceAdminActive(it) }, { it.startActivity(DeviceAdminService.requestDeviceAdminPermission(it)) })
                    )
                ),
                
                PermissionGroupData(
                    title = "Content Safety",
                    description = "Monitor gallery for inappropriate images using on-device AI",
                    icon = "🖼️",
                    permissions = listOf(
                        PermissionData(
                            "Gallery Access",
                            { 
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    com.example.safe_kids.utils.PermissionUtils.hasPermissions(it, arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
                                } else {
                                    com.example.safe_kids.utils.PermissionUtils.hasPermissions(it, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                                }
                            },
                            {
                                val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    android.Manifest.permission.READ_MEDIA_IMAGES
                                } else {
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                (it as? ComponentActivity)?.let { activity ->
                                    androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(permission), 1003)
                                }
                            }
                        )
                    )
                )
            )
        } else {
            listOf(
                PermissionGroupData(
                    title = "Notifications",
                    description = "Receive parental alerts",
                    icon = "🔔",
                    permissions = listOf(
                        PermissionData("Notification Permission", {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                PermissionUtils.hasPermissions(it, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                            } else true
                        }, { openAppInfoSettings(it) })
                    )
                )
            )
        }
        
        // Filter out already granted permissions
        groups.map { group ->
            group.copy(permissions = group.permissions.filter { !it.check(context) })
        }.filter { it.permissions.isNotEmpty() }
    }

    // Still check mandatory status on ORIGINAL list for auto-proceed
    val allMandatoryGranted by remember(refreshTrigger) {
        derivedStateOf {
            val allPermissions = if (role == UserRole.CHILD) {
                listOf(
                    PermissionData("Accessibility", { PermissionUtils.isAccessibilityServiceEnabled(it) }, {}),
                    PermissionData("Usage", { PermissionUtils.isUsageStatsPermissionGranted(it) }, {}),
                    PermissionData("Location", { PermissionUtils.hasLocationPermissions(it) }, {}),
                    PermissionData("Background Location", { PermissionUtils.hasBackgroundLocationPermission(it) }, {}),
                    PermissionData("Battery Optimization", { PermissionUtils.isIgnoringBatteryOptimizations(it) }, {}),
                    PermissionData("Overlays", { PermissionUtils.canDrawOverlays(it) }, {}),
                    PermissionData("Admin", { PermissionUtils.isDeviceAdminActive(it) }, {})
                )
            } else {
                listOf(
                    PermissionData("Notifications", {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionUtils.hasPermissions(it, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                        } else true
                    }, {})
                )
            }
            allPermissions.filter { it.mandatory }.all { it.check(context) }
        }
    }

    // Auto-proceed as soon as all permissions are granted (works on fresh start and after resume)
    LaunchedEffect(allMandatoryGranted) {
        if (allMandatoryGranted) {
            kotlinx.coroutines.delay(600) // brief pause so user sees all toggles go green
            onComplete()
        }
    }

    OnboardingLayout(onSignOut = onSignOut) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Illustration
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // Using the generated image
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground), // Placeholder or use the actual resource if added
                    contentDescription = null,
                    modifier = Modifier.size(180.dp)
                )
                // Note: In a real app I'd use the generated image path, 
                // but since I can't add to R.drawable, I'll use a placeholder or AsyncImage if I had a URL.
                // Manual Bitmap loader to avoid Coil dependency issues
                val illustrationPath = "C:/Users/MURAD/.gemini/antigravity/brain/07dc6e76-c2ab-43e9-b1e3-427c057779e1/permissions_illustration_1777191390443.png"
                val bitmap = remember {
                    try {
                        BitmapFactory.decodeFile(illustrationPath)
                    } catch (e: Exception) {
                        null
                    }
                }
                
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(180.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(180.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Required Permissions",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1E293B)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "To ensure Safe Kids functions properly, please grant the following permissions.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            
            // Show only the first group of missing permissions to keep the flow focused
            filteredGroups.firstOrNull()?.let { group ->
                PermissionGroup(
                    title = group.title,
                    description = group.description,
                    permissions = group.permissions,
                    icon = group.icon,
                    refreshTrigger = refreshTrigger
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = allMandatoryGranted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5C6BC0),
                    disabledContainerColor = Color(0xFFCBD5E1)
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text(
                    text = if (allMandatoryGranted) "Done" else "Grant all permissions to continue",
                    fontWeight = FontWeight.Bold,
                    fontSize = if (allMandatoryGranted) 18.sp else 14.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PermissionEducationDialog(
    icon: String = "📍",
    title: String,
    onAllowAll: () -> Unit,
    onAllowForeground: () -> Unit,
    onDeny: () -> Unit
) {
    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = Color(0xFF2D3135), // Dark background like the design
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF3F4449)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(icon, fontSize = 20.sp)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = title,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                HorizontalDivider(color = Color(0xFF3F4449))
                
                TextButton(
                    onClick = onAllowAll,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("ALLOW ALL THE TIME", color = Color(0xFF7DD3FC), fontWeight = FontWeight.Bold)
                }
                
                HorizontalDivider(color = Color(0xFF3F4449))
                
                TextButton(
                    onClick = onAllowForeground,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("ALLOW ONLY WHILE USING THE APP", color = Color(0xFF7DD3FC), fontWeight = FontWeight.Bold)
                }
                
                HorizontalDivider(color = Color(0xFF3F4449))
                
                TextButton(
                    onClick = onDeny,
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("DENY", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun PermissionGroup(
    title: String,
    description: String,
    permissions: List<PermissionData>,
    icon: String,
    refreshTrigger: Int
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF1F5F9)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = icon, fontSize = 24.sp)
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(text = title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = Color(0xFF1E293B))
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF64748B))
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        permissions.forEach { permission ->
            val isGranted = remember(permission, refreshTrigger) { permission.check(context) }
            var showEducationDialog by remember { mutableStateOf(false) }
            
            if (showEducationDialog && (permission.name.contains("Location") || permission.name.contains("Background"))) {
                PermissionEducationDialog(
                    title = "Safe Kids collects location data to enable real-time tracking, safe zone alerts, and restricted area monitoring even when the app is closed or not in use.",
                    onAllowAll = {
                        showEducationDialog = false
                        permission.action(context)
                    },
                    onAllowForeground = {
                        showEducationDialog = false
                        permission.action(context)
                    },
                    onDeny = { showEducationDialog = false }
                )
            }

            Surface(
                onClick = {
                    if (!isGranted) {
                        if (permission.name.contains("Location")) {
                            showEducationDialog = true
                        } else {
                            permission.action(context)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = if (isGranted) Color(0xFFF0FDF4) else Color(0xFFF8FAFC),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Circle,
                            contentDescription = null,
                            tint = if (isGranted) Color(0xFF22C55E) else Color(0xFFCBD5E1),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = permission.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isGranted) Color(0xFF166534) else Color(0xFF334155),
                            fontWeight = if (isGranted) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                    
                    if (permission.name.contains("Battery")) {
                        Text(
                            text = if (isGranted) "Enabled" else "Configure",
                            color = if (isGranted) Color(0xFF22C55E) else Color(0xFF5C6BC0),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    } else {
                        Switch(
                            checked = isGranted,
                            onCheckedChange = {
                                if (!isGranted) {
                                    if (permission.name.contains("Location")) {
                                        showEducationDialog = true
                                    } else {
                                        permission.action(context)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF22C55E),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFCBD5E1)
                            )
                        )
                    }
                }
            }
        }
    }
}



@Composable
fun BindingCodeScreen(
    authManager: AuthManagerImpl,
    onComplete: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairingCode by remember { mutableStateOf("---------") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var waitingForParent by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    // NEW: Pairing detected – wait for user to press Done before navigating
    var isPairingConfirmed by remember { mutableStateOf(false) }

    val currentUser by authManager.currentUser.collectAsState()

    // Derived: pairing is confirmed when the server pushes parentIds onto this user
    val isPaired = currentUser?.parentIds?.isNotEmpty() == true

    // When Firestore detects isUsed=true on the pairing code doc, show the confirmation step
    DisposableEffect(currentUser?.id) {
        val uid = currentUser?.id ?: return@DisposableEffect onDispose {}
        android.util.Log.d("BindingCodeScreen", "Starting pairing code listener for UID: $uid")
        
        val registration = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection(com.example.safe_kids.auth.AuthConfig.PAIRING_CODES_COLLECTION)
            .document(uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    android.util.Log.e("BindingCodeScreen", "Pairing code listener error", e)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val isUsed = snapshot.getBoolean("isUsed") == true
                    val status = snapshot.getString("status")
                    android.util.Log.d("BindingCodeScreen", "Pairing code update: isUsed=$isUsed, status=$status")
                    
                    // Instead of navigating immediately, show the confirmation step
                    if (isUsed || status == "connected") {
                        isPairingConfirmed = true
                        waitingForParent = false
                        isSaving = false
                    }
                }
            }

        onDispose { 
            android.util.Log.d("BindingCodeScreen", "Removing pairing code listener")
            registration.remove() 
        }
    }

    // When user doc confirms linking, also show confirmation step instead of auto-navigating
    LaunchedEffect(isPaired) {
        if (isPaired && !isPairingConfirmed) {
            android.util.Log.d("BindingCodeScreen", "Device already paired via user doc. Showing confirmation step.")
            isPairingConfirmed = true
            waitingForParent = false
            isSaving = false
        }
    }

    // 120-second timeout while waiting for parent to scan
    LaunchedEffect(waitingForParent) {
        if (!waitingForParent) return@LaunchedEffect
        kotlinx.coroutines.delay(120_000)
        if (!isPaired) {
            timedOut = true
            waitingForParent = false
            isSaving = false
        }
    }

    val generateCode = {
        scope.launch {
            isLoading = true
            errorMsg = null
            timedOut = false
            waitingForParent = false
            try {
                val result = authManager.generatePairingCode()
                if (result.isSuccess) {
                    pairingCode = result.getOrNull() ?: "---------"
                } else {
                    errorMsg = result.exceptionOrNull()?.message ?: "Failed to generate code"
                    android.util.Log.e("BindingCodeScreen", "Code generation failed: $errorMsg")
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "An unexpected error occurred"
                android.util.Log.e("BindingCodeScreen", "Error during code generation", e)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        generateCode()
    }

    OnboardingLayout(onSignOut = onSignOut, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Connect with Parent Device",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Enter this code on the parent's device to start monitoring.",
                textAlign = TextAlign.Center,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(48.dp))

            Surface(
                color = Color(0xFFF8F9FE),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                val formattedCode = if (pairingCode.length == 9) {
                    "${pairingCode.substring(0, 3)} ${pairingCode.substring(3, 6)} ${pairingCode.substring(6, 9)}"
                } else {
                    pairingCode
                }
                Text(
                    text = formattedCode,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 48.dp),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 8.sp,
                    color = Color(0xFF5C6BC0)
                )
            }

            if (isLoading) {
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (errorMsg != null || timedOut) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F2)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Red)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (timedOut) "Pairing timed out. Please refresh the code and try again." else (errorMsg ?: "Failed to generate pairing code"),
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { 
                                // Reset states and try manual check one last time before regenerating
                                scope.launch {
                                    authManager.refreshUserData()
                                    if (currentUser?.parentIds?.isNotEmpty() == true) {
                                        onComplete()
                                    } else {
                                        isSaving = false
                                        waitingForParent = false
                                        timedOut = false
                                        generateCode()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Retry Generation")
                        }
                    }
                }
            } else if (isPairingConfirmed) {
                // Connection confirmed by parent — show success state and wait for Done tap
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Connected! Tap Done to continue.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
            } else if (waitingForParent) {
                // Shown while waiting for the parent to scan the code
                Spacer(modifier = Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Waiting for parent to scan…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(64.dp))

            Button(
                onClick = {
                    if (isPairingConfirmed) {
                        // Pairing visually confirmed — navigate on Done press
                        onComplete()
                    } else if (isPaired) {
                        // Pairing confirmed by Firestore data but UI not updated yet
                        isPairingConfirmed = true
                    } else {
                        // Manually trigger a data refresh to check if parent linked while we were idle
                        scope.launch {
                            isSaving = true
                            authManager.refreshUserData()
                            if (currentUser?.parentIds?.isNotEmpty() == true) {
                                isPairingConfirmed = true
                                isSaving = false
                            } else {
                                waitingForParent = true
                                isSaving = false
                                errorMsg = null
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPairingConfirmed) Color(0xFF4CAF50) else Color(0xFF5C6BC0)
                ),
                shape = RoundedCornerShape(28.dp),
                enabled = !isLoading && !isSaving
            ) {
                if (isSaving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking…")
                    }
                } else if (isPairingConfirmed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Done")
                }
            }

            TextButton(
                onClick = {
                    // Reset all state so the new code generation starts fresh
                    isSaving = false
                    waitingForParent = false
                    timedOut = false
                    generateCode()
                }
            ) {
                Text("Refresh Code", color = Color.Gray)
            }
        }
    }
}


@Composable
fun ModeSelectionCard(
    title: String,
    description: String,
    icon: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineLarge
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ParentPairingScreen(
    authManager: AuthManagerImpl,
    onComplete: () -> Unit,
    onSignOut: () -> Unit
) {
    var pairingCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    // NEW: Connection confirmed – wait for user to tap Done before navigating
    var isPairingConfirmed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val currentUser by authManager.currentUser.collectAsState()

    // Real-time listener: show confirmation when a new child appears in the parent's childIds.
    // Instead of auto-navigating, we now set isPairingConfirmed = true so user sees
    // the "Connected! Tap Done to continue" state before proceeding.
    DisposableEffect(currentUser?.id) {
        val parentId = currentUser?.id ?: return@DisposableEffect onDispose {}
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        var previousChildCount = currentUser?.childIds?.size ?: 0

        val registration = db
            .collection(com.example.safe_kids.auth.AuthConfig.USERS_COLLECTION)
            .document(parentId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val childIds = snapshot.get("childIds") as? List<*> ?: emptyList<Any>()
                // Show confirmation when at least one new child ID has been linked
                if (childIds.size > previousChildCount && !isPairingConfirmed) {
                    android.util.Log.d("ParentPairingScreen", "Child linked via real-time listener — showing confirmation")
                    isPairingConfirmed = true
                    isLoading = false
                }
            }

        onDispose { registration.remove() }
    }

    // Helper: mark onboarding done in Firestore, then call onComplete
    fun finishOnboarding() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            authManager.updateUserProfile(mapOf("isOnboardingComplete" to true))
        }
        onComplete() // Trigger local state update and redirection
    }

    OnboardingLayout(onSignOut = onSignOut, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Link Child Device",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Enter the 9-digit code displayed on your child's device to connect them.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = pairingCode,
                onValueChange = { if (it.length <= 9) pairingCode = it.filter { char -> char.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("9-Digit Pairing Code") },
                singleLine = true,
                isError = errorMsg != null
            )

            if (errorMsg != null) {
                Text(
                    text = errorMsg ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Success confirmation card
            if (isPairingConfirmed) {
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                "Device Connected!",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Tap Done to go to your dashboard.",
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = {
                    if (isPairingConfirmed) {
                        // User confirmed — navigate to dashboard
                        finishOnboarding()
                    } else {
                        if (pairingCode.length != 9) {
                            errorMsg = "Code must be 9 digits"
                            return@Button
                        }
                        isLoading = true
                        errorMsg = null
                        scope.launch {
                            try {
                                // 60-second timeout — prevents infinite spinner on slow/offline network
                                val result = kotlinx.coroutines.withTimeout(60_000) {
                                    authManager.usePairingCode(pairingCode, currentUser?.id ?: "")
                                }
                                if (result.isSuccess) {
                                    // Success: show confirmation step instead of immediately navigating.
                                    // Real-time listener above may also fire; isPairingConfirmed guards
                                    // against double-navigation.
                                    isPairingConfirmed = true
                                } else {
                                    errorMsg = result.exceptionOrNull()?.message ?: "Invalid or expired code"
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                errorMsg = "Request timed out. Please check your connection and try again."
                                android.util.Log.e("ParentPairingScreen", "usePairingCode timed out", e)
                            } catch (e: Exception) {
                                errorMsg = e.message ?: "An unexpected error occurred"
                                android.util.Log.e("ParentPairingScreen", "usePairingCode error", e)
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPairingConfirmed) Color(0xFF4CAF50) else Color(0xFF5C6BC0)
                ),
                shape = RoundedCornerShape(28.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Linking…")
                    }
                } else if (isPairingConfirmed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text("Connect Device")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Skip writes isOnboardingComplete=true to Firestore so this screen
            // never appears again on next launch
            TextButton(
                onClick = { finishOnboarding() },
                enabled = !isLoading
            ) {
                Text("Skip for now", color = Color.Gray)
            }
        }
    }
}
