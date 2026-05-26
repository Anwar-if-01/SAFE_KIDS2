package com.example.safe_kids.auth

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.pow
import kotlin.random.Random


data class User(
    val id: String = "",
    val email: String = "",
    val phoneNumber: String? = null,
    val role: UserRole = UserRole.UNDETERMINED,
    val displayName: String? = null,
    val parentIds: List<String> = emptyList(),
    val childIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis(),
    val profileImageUrl: String? = null,
    val isEmailVerified: Boolean = false,
    val preferences: UserPreferences = UserPreferences(),
    val isOnboardingComplete: Boolean = false,
    val deviceName: String? = null,
    val batteryPercentage: Int? = null,
    val connectionType: String? = null,
    val wifiName: String? = null,
    val adminCode: String? = null,
    val birthDate: Long? = null,
    val isProfileSetupComplete: Boolean = false,
    val isProtectionEnabled: Boolean = false,
    val isOnline: Boolean = false
)

data class UserPreferences(
    val biometricEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val locationSharingEnabled: Boolean = true,
    val emergencyContactsEnabled: Boolean = true
)

enum class UserRole {
    PARENT,
    CO_PARENT,
    CHILD,
    UNDETERMINED
}

val UserRole.isParentLike: Boolean
    get() = this == UserRole.PARENT || this == UserRole.CO_PARENT

data class PairingCode(
    val code: String = "",
    val parentId: String = "",
    val childId: String? = null,
    val expiresAt: Long = 0L,
    val isUsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class InviteCode(
    val code: String = "",
    val inviterId: String = "",
    val invitedEmail: String? = null,
    val expiresAt: Long = 0L,
    val isUsed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class ParentChildRelation(
    val parentId: String,
    val childId: String,
    val relationshipType: String = "primary", // primary, backup
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

interface AuthManager {
    val currentUser: StateFlow<User?>
    val isAuthenticated: StateFlow<Boolean>
    val isInitializing: StateFlow<Boolean>
    
    // Initialization
    suspend fun initialize()
    
    // Authentication methods
    suspend fun signInWithEmail(email: String, password: String): Result<User>
    suspend fun signInWithGoogle(): Result<User>
    suspend fun signUp(email: String, password: String, role: UserRole, displayName: String): Result<User>
    suspend fun signOut()
    suspend fun resetPassword(email: String): Result<Boolean>
    
    // Role-based access control
    fun hasAdminPrivileges(): Boolean
    fun canAccessParentMode(): Boolean
    fun canModifyChildSettings(): Boolean
    
    // Parent-Child pairing
    suspend fun generatePairingCode(): Result<String>
    suspend fun usePairingCode(code: String, childId: String): Result<Boolean>
    suspend fun linkChildToParent(parentId: String, childId: String, relationshipType: String = "primary"): Result<Boolean>
    suspend fun getChildrenForParent(parentId: String): Result<List<User>>
    suspend fun getParentsForChild(childId: String): Result<List<User>>
    suspend fun unbindChild(parentId: String, childId: String): Result<Boolean>
    
    // Co-parent invitation and management
    suspend fun generateCoParentInviteCode(invitedEmail: String? = null): Result<String>
    suspend fun useCoParentInviteCode(code: String): Result<Boolean>
    suspend fun getCoParents(): Result<List<User>>
    suspend fun removeCoParent(coParentId: String): Result<Boolean>
    
    // Icon visibility
    fun isAppIconHidden(): Boolean
    fun setAppIconHidden(hidden: Boolean)
    
    // Security
    suspend fun enableBiometricAuth(activity: FragmentActivity): Result<Boolean>
    suspend fun verifyLocalSecurity(activity: FragmentActivity): Result<Boolean>
    fun isPasswordStrong(password: String): Boolean
    
    // Admin PIN
    suspend fun setAdminCode(code: String): Result<Boolean>
    suspend fun verifyAdminCode(code: String): Boolean
    
    // User management
    fun getCurrentUser(): User?
    suspend fun updateUserProfile(updates: Map<String, Any?>, targetUserId: String? = null): Result<Boolean>
    suspend fun updateChildProfile(childId: String, updates: Map<String, Any?>): Result<Boolean>
    suspend fun updateUserPreferences(preferences: UserPreferences): Result<Boolean>
    
    // Account Lifecycle
    suspend fun deleteAccount(): Result<Boolean>
    
    // Session management
    suspend fun refreshUserData(): Result<User>
    fun isSessionValid(): Boolean
    val sessionManager: SessionManager
}

class AuthManagerImpl(private val context: Context) : AuthManager {
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    override val sessionManager = SessionManager(context)
    private val deviceStateManager = com.example.safe_kids.data.DeviceStateManager(context)
    private var userListener: ListenerRegistration? = null
    
    private lateinit var googleSignInClient: GoogleSignInClient
    
    private val _currentUser = MutableStateFlow<User?>(null)
    override val currentUser: StateFlow<User?> = _currentUser

    // Initialize as false, update asynchronously
    private val _isAuthenticated = MutableStateFlow(false) 
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _isInitializing = MutableStateFlow(true)
    override val isInitializing: StateFlow<Boolean> = _isInitializing

    companion object {
        private const val TAG = "AuthManager"
    }

    override suspend fun initialize() {
        _isInitializing.value = true
        withContext(Dispatchers.IO) {
            try {
                // Configure Google Sign-In
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(AuthConfig.GOOGLE_WEB_CLIENT_ID)
                    .requestEmail()
                    .build()
                
                googleSignInClient = GoogleSignIn.getClient(context, gso)
                
                // FAST-PATH: Load from Local Prefs (SharedPreferences) - INSTANT
                val cachedUser = sessionManager.getCachedUser()
                if (cachedUser != null) {
                    Log.d(TAG, "FAST-PATH: User loaded from SharedPreferences. Unblocking UI.")
                    _currentUser.value = cachedUser
                    _isAuthenticated.value = true
                    _isInitializing.value = false // UNBLOCK UI INSTANTLY
                    
                    deviceStateManager.updateBinding(cachedUser.parentIds.isNotEmpty())
                    deviceStateManager.updateProfileCompletion(cachedUser.isProfileSetupComplete)
                    deviceStateManager.updatePermissions(sessionManager.arePermissionsGranted())
                }
                
                // Check for existing authentication in Firebase
                val currentFirebaseUser = firebaseAuth.currentUser
                if (currentFirebaseUser != null) {
                    
                    if (cachedUser == null) {
                         // Fallback to Firestore cache if Prefs missing
                         Log.d(TAG, "Fast-path miss. Attempting Firestore cache load...")
                         val fsCachedUser = loadUserFromFirestore(currentFirebaseUser.uid, Source.CACHE)
                         if (fsCachedUser != null) {
                             _currentUser.value = fsCachedUser
                             _isAuthenticated.value = true
                             _isInitializing.value = false
                             // Update prefs for next time
                             sessionManager.startSession(fsCachedUser.id, currentFirebaseUser.uid, fsCachedUser)
                             
                             deviceStateManager.updateBinding(fsCachedUser.parentIds.isNotEmpty())
                             deviceStateManager.updateProfileCompletion(fsCachedUser.isProfileSetupComplete)
                             deviceStateManager.updatePermissions(sessionManager.arePermissionsGranted())
                         }
                    }

                    // Always Refresh from SERVER in background
                    val refreshJob = launch {
                        Log.d(TAG, "Starting background server refresh...")
                        try {
                            val serverUser = loadUserFromFirestore(currentFirebaseUser.uid, Source.SERVER)
                            if (serverUser != null) {
                                _currentUser.value = serverUser
                                _isAuthenticated.value = true // Ensure auth is true
                                // Update fast-path cache
                                sessionManager.startSession(serverUser.id, currentFirebaseUser.uid, serverUser)
                                Log.d(TAG, "Background refresh complete. Starting real-time listener...")
                                startUserListener(serverUser.id)
                            } else {
                                Log.w(TAG, "Server refresh returned null user.")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Background refresh failed", e)
                        } finally {
                            // SAFEGUARD: Ensure UI is definitely unblocked after background work finishes
                            if (_isInitializing.value) {
                                Log.d(TAG, "Unblocking UI from background refresh finally block.")
                                _isInitializing.value = false
                            }
                        }
                    }
                    
                    // On fresh install OR if cache is incomplete, we MUST wait for the server refresh
                    // to avoid showing the onboarding screen incorrectly.
                    val isCacheIncomplete = cachedUser?.let { 
                        it.role == UserRole.UNDETERMINED || !it.isProfileSetupComplete 
                    } ?: true
                    
                    if (isCacheIncomplete) {
                        try {
                            Log.d(TAG, "Cache missing or incomplete. Waiting for server sync...")
                            withTimeout(15000L) { // Wait up to 15 seconds for server sync
                                refreshJob.join()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Timed out waiting for server sync during startup")
                            _isInitializing.value = false
                        }
                    }
                    
                    // CRITICAL: If we haven't loaded from FastPath OR Cache yet, we might still be initializing.
                    // However, we launched a background job for Server.
                    // If we want to wait for Server before showing UI (when cache is empty), we must wait.
                    // BUT, if network hangs, we must timeout.
                    // The 'launch' above is async. So execution continues here immediately.
                    
                    if (cachedUser == null) {
                         // We checked FastPath (null), tried FirestoreCache.
                         // If FirestoreCache was also null (we didn't set isInitializing=false inside that block),
                         // then we are relying on the background job.
                         // WAITING HERE IS DANGEROUS unless we track the job.
                         // Better approach for first-run: Let isInitializing stay true, and let the background job unset it.
                         // The background job now has a timeout (in loadUserFromFirestore).
                         // So it won't hang forever.
                    } else {
                        // Cached user found, isInitializing already false.
                    }
                } else {
                    // No Firebase user
                    sessionManager.endSession()
                    _currentUser.value = null
                    _isAuthenticated.value = false
                    _isInitializing.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AuthManager", e)
                _isInitializing.value = false
            } finally {
                 // Double check safeguard
                 if (_isInitializing.value && firebaseAuth.currentUser == null) {
                     _isInitializing.value = false
                 }
            }
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): Result<User> {
        return try {
            // Check if account is locked
            if (!sessionManager.isSessionValid() && sessionManager.getRemainingLockTime() > 0) {
                val remainingMinutes = sessionManager.getRemainingLockTime() / (60 * 1000)
                return Result.failure(Exception("Account locked. Try again in $remainingMinutes minutes."))
            }
            
            withTimeout(30000L) { // 30 seconds timeout
                val authResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
                val firebaseUser = authResult.user ?: throw Exception("Authentication failed")
                
                var user = loadUserFromFirestore(firebaseUser.uid)
                
                if (user == null) {
                    // Recover missing profile - Try to infer role
                    Log.w(TAG, "User profile missing in Firestore for auth user: ${firebaseUser.uid}. Attempting recovery.")
                    
                    var recoveredRole = UserRole.PARENT // Default
                    
                    try {
                        // Check if this user is a child in any relationship
                        val asChild = firestore.collection(AuthConfig.PARENT_CHILD_RELATIONS_COLLECTION)
                            .whereEqualTo("childId", firebaseUser.uid)
                            .limit(1)
                            .get()
                            .await()
                        
                        if (!asChild.isEmpty) {
                            recoveredRole = UserRole.CHILD
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check relationships during recovery", e)
                    }
                    
                    user = User(
                        id = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        role = recoveredRole,
                        displayName = firebaseUser.displayName,
                        isEmailVerified = firebaseUser.isEmailVerified
                    )
                    saveUserToFirestore(user)
                }
                
                if (user != null) {
                    // Reset failed attempts on successful login
                    sessionManager.resetFailedAttempts()
                    
                    // Start new session
                    sessionManager.startSession(user.id, firebaseUser.uid, user)
                    
                    _currentUser.value = user
                    _isAuthenticated.value = true
                    startUserListener(user.id)
                    Result.success(user)
                } else {
                    Result.failure(Exception("Failed to recover user profile"))
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Sign in timed out", e)
            Result.failure(Exception("Sign in timed out. Please check your connection."))
        } catch (e: Exception) {
            Log.e(TAG, "Email sign in failed", e)
            
            // Record failed attempt
            val isLocked = sessionManager.recordFailedAttempt()
            if (isLocked) {
                val lockMinutes = AuthConfig.LOCKOUT_DURATION_MINUTES
                return Result.failure(Exception("Too many failed attempts. Account locked for $lockMinutes minutes."))
            }
            
            val remainingAttempts = AuthConfig.MAX_FAILED_ATTEMPTS - sessionManager.getFailedAttempts()
            Result.failure(Exception("${e.message}. $remainingAttempts attempts remaining."))
        }
    }

    override suspend fun signInWithGoogle(): Result<User> {
        return try {
            val signInIntent = googleSignInClient.signInIntent
            // Note: This would need to be handled in the Activity with startActivityForResult
            // For now, we'll return a placeholder implementation
            Result.failure(Exception("Google Sign-In requires Activity context"))
        } catch (e: Exception) {
            Log.e(TAG, "Google sign in failed", e)
            Result.failure(e)
        }
    }

    // Helper method for Google Sign-In result handling (to be called from Activity)
    suspend fun handleGoogleSignInResult(idToken: String, preferredRole: UserRole? = null): Result<User> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: return Result.failure(Exception("Authentication failed"))
            
            // Check if user exists in Firestore
            var user = loadUserFromFirestore(firebaseUser.uid)
            
            if (user == null) {
                // Create new user profile for Google Sign-In with UNDETERMINED role
                user = User(
                    id = firebaseUser.uid,
                    email = firebaseUser.email ?: "",
                    role = UserRole.UNDETERMINED,
                    displayName = firebaseUser.displayName,
                    profileImageUrl = firebaseUser.photoUrl?.toString(),
                    isEmailVerified = firebaseUser.isEmailVerified
                )
                saveUserToFirestore(user)
            }
            
            // Start session
            sessionManager.startSession(user.id, firebaseUser.uid, user)
            _currentUser.value = user
            _isAuthenticated.value = true
            startUserListener(user.id)
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign in credential handling failed", e)
            Result.failure(e)
        }
    }

    override suspend fun signUp(email: String, password: String, role: UserRole, displayName: String): Result<User> {
        return try {
            // Validate password using new validation system
            val validationResult = PasswordValidator.validate(password)
            if (!validationResult.isValid) {
                return Result.failure(Exception(validationResult.errors.joinToString("; ")))
            }
            
            withTimeout(30000L) { // 30 seconds timeout
                Log.d(TAG, "Creating user in Firebase Auth...")
                val authResult = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
                val firebaseUser = authResult.user ?: throw Exception("User creation failed")
                Log.d(TAG, "Firebase Auth User created: ${firebaseUser.uid}")
                
                // Create user profile with requested role
                val user = User(
                    id = firebaseUser.uid,
                    email = email,
                    role = role,
                    displayName = displayName,
                    isEmailVerified = firebaseUser.isEmailVerified
                )
                
                Log.d(TAG, "Saving user to Firestore with role: $role...")
                
                try {
                    firestore.collection(AuthConfig.USERS_COLLECTION)
                        .document(user.id)
                        .set(user, SetOptions.merge())
                        .await()
                    Log.d(TAG, "User saved to Firestore successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save user to Firestore, rolling back Auth account", e)
                    try {
                        firebaseUser.delete().await()
                    } catch (deleteError: Exception) {
                        Log.e(TAG, "Failed to rollback partially created Firebase Auth account", deleteError)
                    }
                    firebaseAuth.signOut()
                    return@withTimeout Result.failure(Exception("Registration failed. Please try again."))
                }
                
                Log.d(TAG, "Starting session...")
                sessionManager.startSession(user.id, firebaseUser.uid, user)
                
                _currentUser.value = user
                _isAuthenticated.value = true
                startUserListener(user.id)
                Log.d(TAG, "Auth state updated for ${user.email} with role ${user.role}")
                
                Result.success(user)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Sign up timed out", e)
            Result.failure(Exception("Sign up timed out. Please check your connection."))
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            Result.failure(e)
        }
    }

    override suspend fun signOut() {
        try {
            firebaseAuth.signOut()
            if (::googleSignInClient.isInitialized) {
                googleSignInClient.signOut().await()
            }
            stopUserListener()

            // Wipe ALL local state — auth session, cached role, and all onboarding flags.
            // This is the critical step that prevents the next user from being routed
            // to a dashboard that belonged to the previous session.
            sessionManager.clearAllState()

            _currentUser.value = null
            _isAuthenticated.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
        }
    }

    override suspend fun resetPassword(email: String): Result<Boolean> {
        return try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed", e)
            Result.failure(e)
        }
    }

    override fun hasAdminPrivileges(): Boolean {
        val user = _currentUser.value
        return user?.role?.isParentLike == true
    }

    override fun canAccessParentMode(): Boolean {
        return _currentUser.value?.role?.isParentLike == true
    }

    override fun canModifyChildSettings(): Boolean {
        val user = _currentUser.value
        return user?.role?.isParentLike == true
    }

    override suspend fun generatePairingCode(): Result<String> {
        val currentUser = _currentUser.value ?: return Result.failure(Exception("User session not found"))
        
        Log.d(TAG, "Generating pairing code for ${currentUser.id}")
        
        // Generate a safe 9-digit code
        val code = (1..AuthConfig.PAIRING_CODE_LENGTH)
            .map { kotlin.random.Random.nextInt(0, 10) }
            .joinToString("")
        
        val expiresAt = System.currentTimeMillis() + (AuthConfig.PAIRING_CODE_EXPIRY_MINUTES * 60 * 1000)
        
        val pairingData = PairingCode(
            code = code,
            parentId = if (currentUser.role.isParentLike) currentUser.id else "",
            childId = if (currentUser.role == UserRole.CHILD) currentUser.id else null,
            expiresAt = expiresAt
        )

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            firestore.collection(AuthConfig.PAIRING_CODES_COLLECTION)
                .document(currentUser.id)
                .set(pairingData)
                .addOnSuccessListener {
                    Log.d(TAG, "Pairing code $code saved to Firestore")
                    if (continuation.isActive) continuation.resume(Result.success(code)) {}
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to save pairing code", e)
                    if (continuation.isActive) continuation.resume(Result.failure(e)) {}
                }
        }
    }


    override fun isAppIconHidden(): Boolean = sessionManager.isAppIconHidden()

    override fun setAppIconHidden(hidden: Boolean) = sessionManager.setAppIconHidden(hidden)
    override suspend fun usePairingCode(code: String, callerId: String): Result<Boolean> {
        Log.d(TAG, "Attempting to use pairing code: $code")
        
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            firestore.collection(AuthConfig.PAIRING_CODES_COLLECTION)
                .whereEqualTo("code", code.trim())
                .limit(1)
                .get()
                .addOnSuccessListener { querySnapshot ->
                    if (querySnapshot.isEmpty) {
                        continuation.resume(Result.failure(Exception("Code not found. Please check the digits and ensure the other device is online."))) {}
                        return@addOnSuccessListener
                    }
                    
                    val pairingDoc = querySnapshot.documents[0]
                    val pairingData = pairingDoc.toObject(PairingCode::class.java)
                        ?: return@addOnSuccessListener continuation.resume(Result.failure(Exception("Invalid data format"))) {}
                    
                    if (pairingData.isUsed) {
                        continuation.resume(Result.failure(Exception("This code has already been used."))) {}
                        return@addOnSuccessListener
                    }
                    
                    if (System.currentTimeMillis() > pairingData.expiresAt) {
                        continuation.resume(Result.failure(Exception("This code has expired. Please generate a new one."))) {}
                        return@addOnSuccessListener
                    }
                    
                    // Linking logic
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            val linkResult = if (pairingData.parentId.isNotEmpty()) {
                                linkChildToParent(pairingData.parentId, callerId)
                            } else {
                                linkChildToParent(callerId, pairingData.childId ?: "")
                            }
                            
                            if (linkResult.isSuccess) {
                                firestore.collection(AuthConfig.PAIRING_CODES_COLLECTION)
                                    .document(pairingDoc.id)
                                    .update(mapOf("isUsed" to true, "status" to "connected"))
                                    .await()
                                
                                refreshUserData()
                                if (continuation.isActive) continuation.resume(Result.success(true)) {}
                            } else {
                                if (continuation.isActive) continuation.resume(Result.failure(linkResult.exceptionOrNull() ?: Exception("Linking failed"))) {}
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(Result.failure(e)) {}
                        }
                    }
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resume(Result.failure(e)) {}
                }
        }
    }


    override suspend fun linkChildToParent(parentId: String, childId: String, relationshipType: String): Result<Boolean> {
        return try {
            val relation = ParentChildRelation(
                parentId = parentId,
                childId = childId,
                relationshipType = relationshipType
            )
            
            // Save relationship
            firestore.collection(AuthConfig.PARENT_CHILD_RELATIONS_COLLECTION)
                .add(relation)
                .await()
            
            // Update parent's child list using 'set' with merge to avoid NOT_FOUND errors
            firestore.collection(AuthConfig.USERS_COLLECTION)
                .document(parentId)
                .set(
                    mapOf("childIds" to com.google.firebase.firestore.FieldValue.arrayUnion(childId)), 
                    SetOptions.merge()
                )
                .await()
            
            // Update child's parent list and preserve onboarding until permissions are completed.
            firestore.collection(AuthConfig.USERS_COLLECTION)
                .document(childId)
                .set(
                    mapOf(
                        "parentIds" to FieldValue.arrayUnion(parentId),
                        "parentUid" to parentId,
                        "isPaired" to true,
                        "onboardingStep" to "PERMISSIONS"
                    ),
                    SetOptions.merge()
                )
                .await()
            
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to link child to parent", e)
            Result.failure(e)
        }
    }

    override suspend fun unbindChild(parentId: String, childId: String): Result<Boolean> {
        return try {
            Log.d(TAG, "Unbinding child $childId from parent $parentId")
            
            // 1. Remove relationship entries
            val relations = firestore.collection(AuthConfig.PARENT_CHILD_RELATIONS_COLLECTION)
                .whereEqualTo("parentId", parentId)
                .whereEqualTo("childId", childId)
                .get()
                .await()
            
            val batch = firestore.batch()
            for (doc in relations.documents) {
                batch.delete(doc.reference)
            }
            
            // 2. Remove child from parent's list
            batch.update(
                firestore.collection(AuthConfig.USERS_COLLECTION).document(parentId),
                "childIds", com.google.firebase.firestore.FieldValue.arrayRemove(childId)
            )
            
            // 3. Remove parent from child's list
            batch.update(
                firestore.collection(AuthConfig.USERS_COLLECTION).document(childId),
                "parentIds", com.google.firebase.firestore.FieldValue.arrayRemove(parentId)
            )

            // 3b. If the child's `parentUid` points to this parent, clear it so lookups fail-safe
            val childDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(childId).get().await()
            val currentParentUid = childDoc.getString("parentUid")
            if (currentParentUid == parentId) {
                batch.update(
                    firestore.collection(AuthConfig.USERS_COLLECTION).document(childId),
                    "parentUid", com.google.firebase.firestore.FieldValue.delete()
                )
            }
            
            batch.commit().await()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind child", e)
            Result.failure(e)
        }
    }

    override suspend fun getChildrenForParent(parentId: String): Result<List<User>> {
        return try {
            val parentDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(parentId).get().await()
            val childIds = parentDoc.get("childIds") as? List<String> ?: emptyList()
            
            val children = mutableListOf<User>()
            for (childId in childIds) {
                val userDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(childId).get().await()
                userDoc.toObject(User::class.java)?.let { children.add(it) }
            }
            
            Result.success(children)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get children for parent", e)
            Result.failure(e)
        }
    }

    override suspend fun getParentsForChild(childId: String): Result<List<User>> {
        return try {
            val childDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(childId).get().await()
            val parentIds = childDoc.get("parentIds") as? List<String> ?: emptyList()
            
            val parents = mutableListOf<User>()
            for (parentId in parentIds) {
                val userDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(parentId).get().await()
                userDoc.toObject(User::class.java)?.let { parents.add(it) }
            }
            
            Result.success(parents)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get parents for child", e)
            Result.failure(e)
        }
    }

    override suspend fun enableBiometricAuth(activity: FragmentActivity): Result<Boolean> {
        return try {
            val biometricHelper = BiometricAuthHelper(activity)
            val availability = biometricHelper.isBiometricAvailable()
            
            when (availability) {
                BiometricAvailability.AVAILABLE -> {
                    // Test biometric authentication first
                    when (val result = biometricHelper.authenticate(
                        title = "Enable Biometric Authentication",
                        subtitle = "Verify your biometric to enable this feature"
                    )) {
                        is BiometricResult.Success -> {
                            val currentUser = _currentUser.value ?: return Result.failure(Exception("User not authenticated"))
                            val updatedPreferences = currentUser.preferences.copy(biometricEnabled = true)
                            updateUserPreferences(updatedPreferences)
                            Result.success(true)
                        }
                        is BiometricResult.Failed -> Result.failure(Exception("Biometric verification failed"))
                        is BiometricResult.Error -> Result.failure(Exception("Biometric error: ${result.errorMessage}"))
                    }
                }
                BiometricAvailability.NO_HARDWARE -> {
                    Result.failure(Exception("Biometric hardware not available"))
                }
                BiometricAvailability.HARDWARE_UNAVAILABLE -> {
                    Result.failure(Exception("Biometric hardware currently unavailable"))
                }
                BiometricAvailability.NONE_ENROLLED -> {
                    Result.failure(Exception("No biometric credentials enrolled. Please set up fingerprint or face unlock in device settings."))
                }
                BiometricAvailability.NOT_AVAILABLE -> {
                    Result.failure(Exception("Biometric authentication not available"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable biometric auth", e)
            Result.failure(e)
        }
    }

    override suspend fun verifyLocalSecurity(activity: FragmentActivity): Result<Boolean> {
        return try {
            val currentUser = _currentUser.value ?: return Result.failure(Exception("User not authenticated"))
            
            if (!currentUser.preferences.biometricEnabled) {
                return Result.success(true) // No biometric required
            }
            
            val biometricHelper = BiometricAuthHelper(activity)
            val availability = biometricHelper.isBiometricAvailable()
            
            if (availability != BiometricAvailability.AVAILABLE) {
                return Result.failure(Exception("Biometric authentication not available"))
            }
            
            when (val result = biometricHelper.authenticate(
                title = "Safe Kids Authentication",
                subtitle = "Verify your identity to access the app"
            )) {
                is BiometricResult.Success -> Result.success(true)
                is BiometricResult.Failed -> Result.failure(Exception("Biometric authentication failed"))
                is BiometricResult.Error -> Result.failure(Exception("Biometric error: ${result.errorMessage}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify local security", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAccount(): Result<Boolean> {
        val user = _currentUser.value ?: return Result.failure(Exception("No authenticated user"))
        val userId = user.id
        
        return try {
            Log.d(TAG, "Starting account deletion for user: $userId")
            
            // 1. Clean up relationships
            val batch = firestore.batch()
            
            // If user is a parent, remove from all children
            if (user.role.isParentLike) {
                for (childId in user.childIds) {
                    batch.update(
                        firestore.collection(AuthConfig.USERS_COLLECTION).document(childId),
                        "parentIds", com.google.firebase.firestore.FieldValue.arrayRemove(userId)
                    )
                }
            }
            
            // If user is a child, remove from all parents
            if (user.role == UserRole.CHILD) {
                for (parentId in user.parentIds) {
                    batch.update(
                        firestore.collection(AuthConfig.USERS_COLLECTION).document(parentId),
                        "childIds", com.google.firebase.firestore.FieldValue.arrayRemove(userId)
                    )
                }
            }
            
            // 2. Delete relationship documents
            val relationsAsParent = firestore.collection(AuthConfig.PARENT_CHILD_RELATIONS_COLLECTION)
                .whereEqualTo("parentId", userId).get().await()
            for (doc in relationsAsParent.documents) batch.delete(doc.reference)
            
            val relationsAsChild = firestore.collection(AuthConfig.PARENT_CHILD_RELATIONS_COLLECTION)
                .whereEqualTo("childId", userId).get().await()
            for (doc in relationsAsChild.documents) batch.delete(doc.reference)
            
            // 3. Delete user-specific data (locations, profiles, etc.)
            batch.delete(firestore.collection(AuthConfig.LOCATIONS_COLLECTION).document(userId))
            batch.delete(firestore.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(userId))
            
            // 4. Delete user document
            batch.delete(firestore.collection(AuthConfig.USERS_COLLECTION).document(userId))
            
            // 5. Commit Firestore changes
            batch.commit().await()
            Log.d(TAG, "Firestore cleanup complete for $userId")
            
            // 5. Delete from Firebase Auth
            val firebaseUser = firebaseAuth.currentUser ?: throw Exception("Auth session lost during deletion")
            firebaseUser.delete().await()
            Log.d(TAG, "Firebase Auth account deleted for $userId")
            
            // 6. Local cleanup
            signOut()
            
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Account deletion failed", e)
            Result.failure(e)
        }
    }

    override fun isPasswordStrong(password: String): Boolean {
        return PasswordValidator.validate(password).isValid
    }

    override suspend fun setAdminCode(code: String): Result<Boolean> {
        val currentUser = _currentUser.value
        if (currentUser == null || currentUser.id.isBlank()) {
            return Result.failure(Exception("User not authenticated"))
        }
        
        return try {
            // Write adminCode and parentalPin to the parent's user document so children can look it up
            val userId = currentUser.id
            val updates = mutableMapOf<String, Any?>("adminCode" to code)
            if (code.isNotEmpty()) {
                updates["parentalPin"] = code
            } else {
                updates["parentalPin"] = null as String?
            }

            try {
                firestore.collection(AuthConfig.USERS_COLLECTION)
                    .document(userId)
                    .set(updates, SetOptions.merge())
                    .await()
                Log.d(TAG, "Saving PIN to path: users/$userId/parentalPin")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write parentalPin to users/$userId", e)
            }

            // Persist locally
            sessionManager.setAdminCode(code)

            // Also ensure the user's in-memory object reflects the change
            try {
                refreshUserData()
            } catch (_: Exception) {}

            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set parental PIN", e)
            Result.failure(e)
        }
    }


    override suspend fun verifyAdminCode(code: String): Boolean {
        val currentUser = _currentUser.value
        
        if (currentUser?.role == UserRole.CHILD && currentUser.parentIds.isNotEmpty()) {
            val parentId = currentUser.parentIds.first()
            try {
                Log.d(TAG, "Reading PIN from path: users/$parentId/parentalPin")
                val parentDoc = firestore.collection("users").document(parentId).get().await()
                val storedPin = parentDoc.getString("parentalPin") ?: parentDoc.getString("adminCode")
                Log.d(TAG, "Stored PIN: $storedPin, Entered PIN: $code")
                if (!storedPin.isNullOrEmpty() && storedPin == code) return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read PIN from parent", e)
            }
            return false
        }
        
        // Fast path: Check local secure storage
        val localCode = sessionManager.getAdminCode()
        if (!localCode.isNullOrEmpty() && localCode == code) return true
        
        if (currentUser != null) {
            try {
                Log.d(TAG, "Reading PIN from path: users/${currentUser.id}/parentalPin")
                val doc = firestore.collection("users").document(currentUser.id).get().await()
                val storedPin = doc.getString("parentalPin") ?: doc.getString("adminCode")
                Log.d(TAG, "Stored PIN: $storedPin, Entered PIN: $code")
                if (!storedPin.isNullOrEmpty() && storedPin == code) return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read own PIN", e)
            }
        }
        
        return false
    }

    override fun getCurrentUser(): User? {
        return _currentUser.value
    }

    override suspend fun updateUserProfile(updates: Map<String, Any?>, targetUserId: String?): Result<Boolean> {
        val userId = targetUserId ?: _currentUser.value?.id ?: return Result.failure(Exception("User not authenticated"))
        
        Log.d(TAG, "Attempting profile update for $userId")
        
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            firestore.collection(AuthConfig.USERS_COLLECTION)
                .document(userId)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Profile update successful in Firestore")
                    // Refresh in background
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            refreshUserData()
                        } catch (e: Exception) {
                            Log.w(TAG, "Background refresh failed: ${e.message}")
                        }
                    }
                    if (continuation.isActive) continuation.resume(Result.success(true)) {}
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Profile update failed in Firestore", e)
                    if (continuation.isActive) continuation.resume(Result.failure(e)) {}
                }
        }
    }

    override suspend fun updateChildProfile(childId: String, updates: Map<String, Any?>): Result<Boolean> {
        return try {
            firestore.collection(AuthConfig.CHILD_PROFILES_COLLECTION).document(childId).update(updates).await()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update child profile", e)
            Result.failure(e)
        }
    }

    override suspend fun updateUserPreferences(preferences: UserPreferences): Result<Boolean> {
        return try {
            val userId = _currentUser.value?.id ?: return Result.failure(Exception("User not authenticated"))
            firestore.collection(AuthConfig.USERS_COLLECTION).document(userId).update("preferences", preferences).await()
            refreshUserData()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preferences", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshUserData(): Result<User> {
        return try {
            val currentUser = _currentUser.value ?: return Result.failure(Exception("User not authenticated"))
            
            val user = loadUserFromFirestore(currentUser.id)
            if (user != null) {
                _currentUser.value = user
                sessionManager.updateUserCache(user)
                sessionManager.updateActivity()
                Result.success(user)
            } else {
                Result.failure(Exception("User not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh user data", e)
            Result.failure(e)
        }
    }

    override suspend fun generateCoParentInviteCode(invitedEmail: String?): Result<String> {
        val currentUser = _currentUser.value ?: return Result.failure(Exception("User session not found"))
        if (currentUser.role != UserRole.PARENT) return Result.failure(Exception("Only primary parents can generate invite codes"))

        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val code = (1..6).map { chars[kotlin.random.Random.nextInt(0, chars.length)] }.joinToString("")
        val expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours for email invites

        val inviteData = InviteCode(
            code = code,
            inviterId = currentUser.id,
            invitedEmail = invitedEmail?.lowercase()?.trim(),
            expiresAt = expiresAt
        )

        return try {
            firestore.collection(AuthConfig.INVITE_CODES_COLLECTION)
                .document(currentUser.id)
                .set(inviteData)
                .await()
            Result.success(code)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun useCoParentInviteCode(code: String): Result<Boolean> {
        val currentUser = _currentUser.value ?: return Result.failure(Exception("User session not found"))
        
        return try {
            val snapshot = firestore.collection(AuthConfig.INVITE_CODES_COLLECTION)
                .whereEqualTo("code", code.trim())
                .limit(1)
                .get()
                .await()

            if (snapshot.isEmpty) return Result.failure(Exception("Invalid invite code"))

            val inviteDoc = snapshot.documents[0]
            val inviteData = inviteDoc.toObject(InviteCode::class.java) ?: return Result.failure(Exception("Invalid data"))

            if (inviteData.isUsed) return Result.failure(Exception("Code already used"))
            if (System.currentTimeMillis() > inviteData.expiresAt) return Result.failure(Exception("Code expired"))

            // Get inviter (primary parent) children
            val childrenSnapshot = firestore.collection(AuthConfig.USERS_COLLECTION)
                .whereArrayContains("parentIds", inviteData.inviterId)
                .whereEqualTo("role", UserRole.CHILD.name)
                .get()
                .await()

            val childIds = childrenSnapshot.documents.map { it.id }
            
            // Link co-parent to these children
            childIds.forEach { childId ->
                linkChildToParent(currentUser.id, childId, "co-parent").getOrThrow()
            }

            // Mark code as used
            firestore.collection(AuthConfig.INVITE_CODES_COLLECTION)
                .document(inviteDoc.id)
                .update("isUsed", true)
                .await()

            // Update co-parent profile
            updateUserProfile(mapOf(
                "role" to UserRole.CO_PARENT.name,
                "isOnboardingComplete" to true,
                "isProfileSetupComplete" to true
            )).getOrThrow()

            sessionManager.setDevicePaired(true)
            sessionManager.setProfileCompleted(true)
            
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCoParents(): Result<List<User>> {
        val currentUser = _currentUser.value ?: return Result.failure(Exception("User session not found"))
        if (currentUser.role != UserRole.PARENT) return Result.failure(Exception("Only primary parents can manage co-parents"))

        return try {
            val parentDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(currentUser.id).get().await()
            val childIds = parentDoc.get("childIds") as? List<String> ?: emptyList()
            if (childIds.isEmpty()) return Result.success(emptyList())

            val coParentsSnapshot = firestore.collection(AuthConfig.USERS_COLLECTION)
                .whereEqualTo("role", UserRole.CO_PARENT.name)
                .get()
                .await()
            
            val coParents = mutableListOf<User>()
            for (doc in coParentsSnapshot.documents) {
                val coParent = doc.toObject(User::class.java) ?: continue
                val overlap = coParent.childIds.intersect(childIds.toSet())
                if (overlap.isNotEmpty()) {
                    coParents.add(coParent)
                }
            }
            Result.success(coParents)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get co-parents", e)
            Result.failure(e)
        }
    }

    override suspend fun removeCoParent(coParentId: String): Result<Boolean> {
        return try {
            Log.d(TAG, "Removing co-parent $coParentId")
            val coParentDoc = firestore.collection(AuthConfig.USERS_COLLECTION).document(coParentId).get().await()
            val childIds = coParentDoc.get("childIds") as? List<String> ?: emptyList()

            val batch = firestore.batch()

            for (childId in childIds) {
                val relations = firestore.collection(AuthConfig.PARENT_CHILD_RELATIONS_COLLECTION)
                    .whereEqualTo("parentId", coParentId)
                    .whereEqualTo("childId", childId)
                    .get()
                    .await()
                
                for (doc in relations.documents) {
                    batch.delete(doc.reference)
                }

                batch.update(
                    firestore.collection(AuthConfig.USERS_COLLECTION).document(childId),
                    "parentIds", com.google.firebase.firestore.FieldValue.arrayRemove(coParentId)
                )
            }
            
            batch.update(
                firestore.collection(AuthConfig.USERS_COLLECTION).document(coParentId),
                mapOf(
                    "childIds" to emptyList<String>(),
                    "role" to UserRole.UNDETERMINED.name
                )
            )
            
            batch.commit().await()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove co-parent", e)
            Result.failure(e)
        }
    }

    override fun isSessionValid(): Boolean {
        return sessionManager.isSessionValid()
    }

    // Private helper methods
    private suspend fun loadUserFromFirestore(userId: String, source: Source = Source.DEFAULT): User? {
        return try {
            val userDoc = withTimeout(30000L) { // 30 seconds generic timeout for Firestore ops
               firestore.collection(AuthConfig.USERS_COLLECTION)
                .document(userId)
                .get(source) // Use specific source
                .await()
            }
            val user = userDoc.toObject(User::class.java)
            // Note: We don't update _currentUser/_isAuthenticated here to allow customized handling in caller
            user
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load user from Firestore for ID: $userId, Source: $source", e)
            null
        }
    }

    private suspend fun saveUserToFirestore(user: User) {
        try {
            firestore.collection(AuthConfig.USERS_COLLECTION)
                .document(user.id)
                .set(user, SetOptions.merge())
                .await()
            Log.d(TAG, "User profile saved to Firestore.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save user to Firestore", e)
            throw e
        }
    }

    private fun intPow(base: Int, exp: Int): Int {
        var res = 1
        for (i in 1..exp) res *= base
        return res
    }

    private fun startUserListener(userId: String) {
        stopUserListener() // Ensure any existing listener is cleaned up
        Log.d(TAG, "Starting real-time SnapshotListener for user: $userId")
        userListener = firestore.collection(AuthConfig.USERS_COLLECTION)
            .document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "User SnapshotListener error", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val user = snapshot.toObject(User::class.java)
                    if (user != null) {
                        Log.d(TAG, "Real-time user update received: isOnboardingComplete=${user.isOnboardingComplete}")
                        _currentUser.value = user
                        // Sync with local session cache and DeviceStateManager
                        sessionManager.updateUserCache(user)
                        kotlinx.coroutines.MainScope().launch {
                            deviceStateManager.updateBinding(user.parentIds.isNotEmpty())
                            deviceStateManager.updateProfileCompletion(user.isProfileSetupComplete)
                            deviceStateManager.updatePermissions(sessionManager.arePermissionsGranted())
                        }
                    }
                }
            }
    }

    private fun stopUserListener() {
        userListener?.remove()
        userListener = null
    }
}

