package com.example.safe_kids.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BiometricAuthHelper(private val activity: FragmentActivity) {
    
    fun isBiometricAvailable(): BiometricAvailability {
        val biometricManager = BiometricManager.from(activity)
        
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricAvailability.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
            else -> BiometricAvailability.NOT_AVAILABLE
        }
    }
    
    suspend fun authenticate(
        title: String = "Biometric Authentication",
        subtitle: String = "Use your fingerprint or face to authenticate",
        negativeButtonText: String = "Cancel"
    ): BiometricResult = suspendCancellableCoroutine { continuation ->
        
        val executor = ContextCompat.getMainExecutor(activity)
        
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (continuation.isActive) {
                    continuation.resume(BiometricResult.Error(errorCode, errString.toString()))
                }
            }
            
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                if (continuation.isActive) {
                    continuation.resume(BiometricResult.Success)
                }
            }
            
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                if (continuation.isActive) {
                    continuation.resume(BiometricResult.Failed)
                }
            }
        })
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .build()
        
        biometricPrompt.authenticate(promptInfo)
        
        continuation.invokeOnCancellation {
            biometricPrompt.cancelAuthentication()
        }
    }
}

enum class BiometricAvailability {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NONE_ENROLLED,
    NOT_AVAILABLE
}

sealed class BiometricResult {
    object Success : BiometricResult()
    object Failed : BiometricResult()
    data class Error(val errorCode: Int, val errorMessage: String) : BiometricResult()
}
