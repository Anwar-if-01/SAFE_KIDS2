package com.example.safe_kids.ui.parent

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.auth.AuthManager
import com.example.safe_kids.services.SOSListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SOSViewModel(
    private val context: Context,
    private val authManager: AuthManager
) : ViewModel() {

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    init {
        viewModelScope.launch {
            authManager.isAuthenticated.collect { authenticated ->
                if (authenticated) {
                    val user = authManager.getCurrentUser()
                    if (user?.role == com.example.safe_kids.auth.UserRole.PARENT || 
                        user?.role == com.example.safe_kids.auth.UserRole.CO_PARENT) {
                        startEmergencyMonitoring()
                    }
                } else {
                    stopEmergencyMonitoring()
                }
            }
        }
    }

    fun startEmergencyMonitoring() {
        val intent = Intent(context, SOSListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _isMonitoring.value = true
    }

    fun stopEmergencyMonitoring() {
        val intent = Intent(context, SOSListenerService::class.java)
        context.stopService(intent)
        _isMonitoring.value = false
    }
}
