package com.example.safe_kids.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.auth.SessionManager
import com.example.safe_kids.auth.UserRole
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class OnboardingStep {
    PERMISSIONS,
    PROFILE_SETUP,
    DEVICE_PAIRING,
    CO_PARENT_INVITE,
    COMPLETED
}

sealed class NavigationEvent {
    object NavigateToParentDashboard : NavigationEvent()
    object NavigateToChildDashboard : NavigationEvent()
    object NavigateToAuth : NavigationEvent()
    object NavigateToRoleSelection : NavigationEvent()
}

class OnboardingViewModel(
    private val authManager: AuthManager,
    context: android.content.Context
) : ViewModel() {
    private val sessionManager = authManager.sessionManager
    private val deviceStateManager = com.example.safe_kids.data.DeviceStateManager(context)

    private val _currentStep = MutableStateFlow<OnboardingStep>(OnboardingStep.PROFILE_SETUP)
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<NavigationEvent>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    fun triggerNavigation(event: NavigationEvent) {
        viewModelScope.launch {
            _navigationEvents.emit(event)
        }
    }

    init {
        determineInitialStep()
        
        // Listen for user changes to keep in sync
        viewModelScope.launch {
            authManager.currentUser.collect { user ->
                user?.let {
                    // Sync server state to local if available
                    if (it.isProfileSetupComplete && !sessionManager.isProfileCompleted()) {
                        sessionManager.setProfileCompleted(true)
                        deviceStateManager.updateProfileCompletion(true)
                    }
                    if (it.parentIds.isNotEmpty()) {
                        if (!sessionManager.isDevicePaired()) {
                            sessionManager.setDevicePaired(true)
                            deviceStateManager.updateBinding(true)
                        }
                    } else if (sessionManager.isDevicePaired() && it.role == UserRole.CHILD) {
                        // UNLINK DETECTED: Device was paired but server says no parents now
                        android.util.Log.w("OnboardingViewModel", "Unlink detected! Resetting pairing state.")
                        sessionManager.setDevicePaired(false)
                        deviceStateManager.updateBinding(false)
                    }
                    
                    // Sync protection state from server to local
                    deviceStateManager.updateProtection(it.isProtectionEnabled)
                    
                    determineInitialStep()
                }
            }
        }
    }

    fun updateProtection(enabled: Boolean) {
        viewModelScope.launch {
            deviceStateManager.updateProtection(enabled)
        }
    }

    fun determineInitialStep() {
        val user = authManager.currentUser.value
        val profileDone = sessionManager.isProfileCompleted()
        val pairingDone = sessionManager.isDevicePaired()
        val permissionsDone = sessionManager.arePermissionsGranted()
        val shouldCheckPermissions = user?.role != UserRole.CHILD

        _currentStep.value = when {
            shouldCheckPermissions && !permissionsDone -> OnboardingStep.PERMISSIONS
            !profileDone -> OnboardingStep.PROFILE_SETUP
            user?.role == UserRole.CO_PARENT && !pairingDone -> OnboardingStep.CO_PARENT_INVITE
            !pairingDone -> OnboardingStep.DEVICE_PAIRING
            else -> OnboardingStep.COMPLETED
        }
    }

    fun completeProfile() {
        sessionManager.setProfileCompleted(true)
        viewModelScope.launch { deviceStateManager.updateProfileCompletion(true) }
        determineInitialStep()
    }

    fun completePairing() {
        sessionManager.setDevicePaired(true)
        viewModelScope.launch { deviceStateManager.updateBinding(true) }
        determineInitialStep()
    }

    fun completePermissions() {
        sessionManager.setPermissionsGranted(true)
        viewModelScope.launch { deviceStateManager.updatePermissions(true) }
        determineInitialStep()
    }

    /**
     * Switch Mode — keeps the user authenticated but resets role and all
     * onboarding flags so MainActivity routes to the "Whose phone is this?" screen.
     * Call this before navigating to MainActivity with CLEAR_TASK flags.
     */
    fun switchMode() {
        sessionManager.clearModeState()
        viewModelScope.launch { deviceStateManager.clearAll() }
        // Immediately reset local state so any observer sees the change now,
        // not after the next determineInitialStep() call.
        _currentStep.value = OnboardingStep.PERMISSIONS
    }
}

class OnboardingViewModelFactory(
    private val authManager: AuthManager,
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnboardingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OnboardingViewModel(authManager, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
