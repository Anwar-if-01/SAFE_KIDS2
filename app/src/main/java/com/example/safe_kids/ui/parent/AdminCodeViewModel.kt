package com.example.safe_kids.ui.parent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.auth.AuthManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AdminCodeState {
    object Idle : AdminCodeState()
    object Loading : AdminCodeState()
    object Success : AdminCodeState()
    data class Error(val message: String) : AdminCodeState()
}

class AdminCodeViewModel(private val authManager: AuthManager) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminCodeState>(AdminCodeState.Idle)
    val uiState: StateFlow<AdminCodeState> = _uiState.asStateFlow()

    private val _hasAdminCode = MutableStateFlow(false)
    val hasAdminCode: StateFlow<Boolean> = _hasAdminCode.asStateFlow()

    init {
        checkAdminCodeStatus()
    }

    private fun checkAdminCodeStatus() {
        viewModelScope.launch {
            val localCode = authManager.sessionManager.getAdminCode()
            if (!localCode.isNullOrEmpty()) {
                _hasAdminCode.value = true
            } else {
                authManager.currentUser.collect { user ->
                    _hasAdminCode.value = !user?.adminCode.isNullOrEmpty()
                }
            }
        }
    }

    /** Save PIN to parent's user doc ONLY as per bug fix instructions. */
    private suspend fun savePinToChildren(pin: String) {
        // Obsolete: We now rely on child reading from users/{parentId}/parentalPin
    }

    fun setAdminCode(code: String) {
        viewModelScope.launch {
            _uiState.value = AdminCodeState.Loading
            if (authManager.getCurrentUser() == null) {
                val refreshResult = authManager.refreshUserData()
                if (refreshResult.isFailure) {
                    _uiState.value = AdminCodeState.Error("Authentication error. Please try again.")
                    return@launch
                }
            }

            if (code.trim().length != 4) {
                _uiState.value = AdminCodeState.Error("Parental PIN must be 4 digits")
                return@launch
            }
            val result = authManager.setAdminCode(code.trim())
            if (result.isSuccess) {
                _hasAdminCode.value = true
                savePinToChildren(code.trim())
                _uiState.value = AdminCodeState.Success
            } else {
                _uiState.value = AdminCodeState.Error(result.exceptionOrNull()?.message ?: "Failed to set code. Check your connection.")
            }
        }
    }

    fun changeAdminCode(oldCode: String, newCode: String) {
        viewModelScope.launch {
            _uiState.value = AdminCodeState.Loading
            val isVerified = authManager.verifyAdminCode(oldCode.trim())
            if (!isVerified) {
                _uiState.value = AdminCodeState.Error("Incorrect old code")
                return@launch
            }

            if (newCode.trim().length != 4) {
                _uiState.value = AdminCodeState.Error("New code must be 4 digits")
                return@launch
            }
            val result = authManager.setAdminCode(newCode.trim())
            if (result.isSuccess) {
                _hasAdminCode.value = true
                savePinToChildren(newCode.trim())
                _uiState.value = AdminCodeState.Success
            } else {
                _uiState.value = AdminCodeState.Error(result.exceptionOrNull()?.message ?: "Failed to change code. Check your connection.")
            }
        }
    }

    fun disableAdminCode(oldCode: String) {
        viewModelScope.launch {
            _uiState.value = AdminCodeState.Loading
            val isVerified = authManager.verifyAdminCode(oldCode.trim())
            if (!isVerified) {
                _uiState.value = AdminCodeState.Error("Incorrect code")
                return@launch
            }

            val result = authManager.setAdminCode("")
            if (result.isSuccess) {
                _hasAdminCode.value = false
                savePinToChildren("")
                _uiState.value = AdminCodeState.Success
            } else {
                _uiState.value = AdminCodeState.Error(result.exceptionOrNull()?.message ?: "Failed to disable code")
            }
        }
    }

    fun resetState() {
        _uiState.value = AdminCodeState.Idle
    }
}

class AdminCodeViewModelFactory(private val authManager: AuthManager) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AdminCodeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AdminCodeViewModel(authManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
